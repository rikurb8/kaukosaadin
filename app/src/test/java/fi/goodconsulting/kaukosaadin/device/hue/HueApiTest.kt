package fi.goodconsulting.kaukosaadin.device.hue

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

class HueApiTest {
    @Test fun lightsReadTheLightResourceWithTheAppKeyHeader() = runBlocking {
        val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
        HueApi(HOST, KEY, executor).lights()

        val request = executor.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://$HOST/clip/v2/resource/light", request.url.toString())
        assertEquals(KEY, request.header(HueProtocol.API_KEY_HEADER))
    }

    @Test fun roomsAndGroupedLightsReadTheirOwnResources() = runBlocking {
        val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
        val api = HueApi(HOST, KEY, executor)
        api.rooms()
        api.groupedLights()

        assertEquals(
            listOf(
                "https://$HOST/clip/v2/resource/room",
                "https://$HOST/clip/v2/resource/grouped_light",
            ),
            executor.requests.map { it.url.toString() },
        )
    }

    @Test fun aSuccessfulReadDecodesTheModel() = runBlocking {
        val body =
            """{"errors":[],"data":[
                {"id":"light-1","type":"light","metadata":{"name":"Ceiling"},"on":{"on":true},"dimming":{"brightness":42.5}}
            ]}"""
        val result = HueApi(HOST, KEY, FakeExecutor { HueHttpResponse(200, body) }).lights()
        assertEquals(HueResult.Ok(listOf(HueLight("light-1", "Ceiling", on = true, brightness = 42.5))), result)
    }

    @Test fun aV2ErrorEnvelopeBecomesAFailureWithItsType() = runBlocking {
        val body = """{"errors":[{"description":"rejected","type":7}],"data":[]}"""
        val result = HueApi(HOST, KEY, FakeExecutor { HueHttpResponse(200, body) }).lights()
        assertEquals(HueResult.Failure("The bridge refused the request (Hue error 7)."), result)
    }

    @Test fun anErrorStatusBecomesAFailureKeyedOnTheStatus() = runBlocking {
        val result = HueApi(HOST, KEY, FakeExecutor { HueHttpResponse(401, "") }).lights()
        assertEquals(HueResult.Failure("The bridge rejected the app key. Pair the bridge again."), result)
    }

    @Test fun aMalformedSuccessBodyBecomesAFailure() = runBlocking {
        val result = HueApi(HOST, KEY, FakeExecutor { HueHttpResponse(200, "not json") }).lights()
        assertEquals(HueResult.Failure(HueErrors.unreadable()), result)
    }

    @Test fun anUnreachableBridgeIsAFailureNotAThrownException() = runBlocking {
        val result = HueApi(HOST, KEY, FakeExecutor { throw IOException("no route") }).lights()
        assertEquals(HueResult.Failure(HueErrors.unreachable()), result)
    }

    @Test fun aTlsFailureReportsTheTrustMessage() = runBlocking {
        val result = HueApi(HOST, KEY, FakeExecutor { throw SSLHandshakeException("bad cert") }).lights()
        assertEquals(HueResult.Failure(HueErrors.trustChanged()), result)
    }

    @Test fun setOnPutsTheOnBodyToTheLightResource() = runBlocking {
        val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
        val result = HueApi(HOST, KEY, executor).setOn(HueTarget.Light("light-1"), on = true)

        assertEquals(HueResult.Ok(Unit), result)
        val request = executor.requests.single()
        assertEquals("PUT", request.method)
        assertEquals("https://$HOST/clip/v2/resource/light/light-1", request.url.toString())
        assertEquals(KEY, request.header(HueProtocol.API_KEY_HEADER))
        assertEquals("""{"on":{"on":true}}""", JSONObject(request.bodyText()).toString())
    }

    @Test fun setOnCanTargetAGroupedLight() = runBlocking {
        val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
        HueApi(HOST, KEY, executor).setOn(HueTarget.Group("grouped-2"), on = false)

        val request = executor.requests.single()
        assertEquals("https://$HOST/clip/v2/resource/grouped_light/grouped-2", request.url.toString())
        assertEquals("""{"on":{"on":false}}""", JSONObject(request.bodyText()).toString())
    }

    @Test fun setBrightnessSendsOnlyDimmingAndNeverOn() = runBlocking {
        val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
        val result = HueApi(HOST, KEY, executor).setBrightness(HueTarget.Light("light-1"), 63)

        assertEquals(HueResult.Ok(Unit), result)
        val body = JSONObject(executor.requests.single().bodyText())
        assertEquals(63, body.getJSONObject("dimming").getInt("brightness"))
        // The off-state assumption: a brightness write must not carry an on field.
        assertFalse(body.has("on"))
    }

    @Test fun anOutOfRangeBrightnessFailsWithoutTouchingTheBridge() = runBlocking {
        val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
        val api = HueApi(HOST, KEY, executor)

        assertEquals(HueResult.Failure(HueErrors.brightnessOutOfRange()), api.setBrightness(HueTarget.Light("light-1"), 101))
        assertEquals(HueResult.Failure(HueErrors.brightnessOutOfRange()), api.setBrightness(HueTarget.Light("light-1"), -1))
        assertTrue(executor.requests.isEmpty())
    }

    @Test fun aFailedCommandIsSentOnceAndNeverReplayed() = runBlocking {
        val executor = FakeExecutor { HueHttpResponse(503, """{"errors":[{"description":"down","type":901}]}""") }
        val result = HueApi(HOST, KEY, executor).setOn(HueTarget.Light("light-1"), on = true)

        assertTrue(result is HueResult.Failure)
        assertEquals(1, executor.requests.size)
    }

    @Test fun eachPressSendsItsOwnCommandAndNothingElse() = runBlocking {
        val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
        val api = HueApi(HOST, KEY, executor)
        api.setOn(HueTarget.Light("light-1"), on = true)
        api.setOn(HueTarget.Light("light-1"), on = false)

        assertEquals(2, executor.requests.size)
        assertEquals(listOf(true, false), executor.requests.map { JSONObject(it.bodyText()).getJSONObject("on").getBoolean("on") })
    }

    private fun Request.bodyText(): String {
        val buffer = Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private class FakeExecutor(
        private val respond: (Request) -> HueHttpResponse,
    ) : HueRequestExecutor {
        val requests = mutableListOf<Request>()

        override fun execute(request: Request): HueHttpResponse {
            requests += request
            return respond(request)
        }
    }

    private companion object {
        const val HOST = "192.168.1.42"
        const val KEY = "a4e08834-0893-4013-b646-738582ec15c9"
        const val EMPTY_ENVELOPE = """{"errors":[],"data":[]}"""
    }
}
