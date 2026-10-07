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
    @Test fun lightsReadTheLightResourceWithTheAppKeyHeader() =
        runBlocking {
            val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
            HueApi(HOST, KEY, executor).lights()

            val request = executor.requests.single()
            assertEquals("GET", request.method)
            assertEquals("https://$HOST/clip/v2/resource/light", request.url.toString())
            assertEquals(KEY, request.header(HueProtocol.API_KEY_HEADER))
        }

    @Test fun roomsZonesAndGroupedLightsReadTheirOwnResources() =
        runBlocking {
            val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
            val api = HueApi(HOST, KEY, executor)
            api.rooms()
            api.zones()
            api.groupedLights()

            assertEquals(
                listOf(
                    "https://$HOST/clip/v2/resource/room",
                    "https://$HOST/clip/v2/resource/zone",
                    "https://$HOST/clip/v2/resource/grouped_light",
                ),
                executor.requests.map { it.url.toString() },
            )
        }

    @Test fun aSuccessfulReadDecodesTheModel() =
        runBlocking {
            val body =
                """{"errors":[],"data":[
                {"id":"light-1","type":"light","metadata":{"name":"Ceiling"},"on":{"on":true},"dimming":{"brightness":42.5}}
            ]}"""
            val result = HueApi(HOST, KEY, FakeExecutor { HueHttpResponse(200, body) }).lights()
            assertEquals(HueResult.Ok(listOf(HueLight("light-1", "Ceiling", on = true, brightness = 42.5))), result)
        }

    @Test fun aV2ErrorEnvelopeBecomesAFailureWithItsType() =
        runBlocking {
            val body = """{"errors":[{"description":"rejected","type":7}],"data":[]}"""
            val result = HueApi(HOST, KEY, FakeExecutor { HueHttpResponse(200, body) }).lights()
            assertEquals(HueResult.Failure("The bridge refused the request (Hue error 7)."), result)
        }

    @Test fun anErrorStatusBecomesAFailureKeyedOnTheStatus() =
        runBlocking {
            val result = HueApi(HOST, KEY, FakeExecutor { HueHttpResponse(401, "") }).lights()
            assertEquals(HueResult.Failure("The bridge rejected the app key. Pair the bridge again."), result)
        }

    @Test fun aMalformedSuccessBodyBecomesAFailure() =
        runBlocking {
            val result = HueApi(HOST, KEY, FakeExecutor { HueHttpResponse(200, "not json") }).lights()
            assertEquals(HueResult.Failure(HueErrors.UNREADABLE), result)
        }

    @Test fun anUnreachableBridgeIsAFailureNotAThrownException() =
        runBlocking {
            val result = HueApi(HOST, KEY, FakeExecutor { throw IOException("no route") }).lights()
            assertEquals(HueResult.Failure(HueErrors.UNREACHABLE), result)
        }

    @Test fun aTlsFailureReportsTheTrustMessage() =
        runBlocking {
            val result = HueApi(HOST, KEY, FakeExecutor { throw SSLHandshakeException("bad cert") }).lights()
            assertEquals(HueResult.Failure(HueErrors.TRUST_CHANGED), result)
        }

    @Test fun setOnPutsTheOnBodyToTheLightResource() =
        runBlocking {
            val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
            val result = HueApi(HOST, KEY, executor).setOn(HueCommandTarget.Light("light-1"), on = true)

            assertEquals(HueResult.Ok(Unit), result)
            val request = executor.requests.single()
            assertEquals("PUT", request.method)
            assertEquals("https://$HOST/clip/v2/resource/light/light-1", request.url.toString())
            assertEquals(KEY, request.header(HueProtocol.API_KEY_HEADER))
            assertEquals("""{"on":{"on":true}}""", JSONObject(request.bodyText()).toString())
        }

    @Test fun setOnCanTargetAGroupedLight() =
        runBlocking {
            val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
            HueApi(HOST, KEY, executor).setOn(HueCommandTarget.Group("grouped-2"), on = false)

            val request = executor.requests.single()
            assertEquals("https://$HOST/clip/v2/resource/grouped_light/grouped-2", request.url.toString())
            assertEquals("""{"on":{"on":false}}""", JSONObject(request.bodyText()).toString())
        }

    @Test fun setBrightnessSendsOnlyDimmingAndNeverOn() =
        runBlocking {
            val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
            val result = HueApi(HOST, KEY, executor).setBrightness(HueCommandTarget.Light("light-1"), 63)

            assertEquals(HueResult.Ok(Unit), result)
            val body = JSONObject(executor.requests.single().bodyText())
            assertEquals(63, body.getJSONObject("dimming").getInt("brightness"))
            // The off-state assumption: a brightness write must not carry an on field.
            assertFalse(body.has("on"))
        }

    @Test fun anOutOfRangeBrightnessFailsWithoutTouchingTheBridge() =
        runBlocking {
            val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
            val api = HueApi(HOST, KEY, executor)

            assertEquals(HueResult.Failure(HueErrors.BRIGHTNESS_OUT_OF_RANGE), api.setBrightness(HueCommandTarget.Light("light-1"), 101))
            assertEquals(HueResult.Failure(HueErrors.BRIGHTNESS_OUT_OF_RANGE), api.setBrightness(HueCommandTarget.Light("light-1"), -1))
            assertTrue(executor.requests.isEmpty())
        }

    @Test fun aFailedCommandIsSentOnceAndNeverReplayed() =
        runBlocking {
            val executor = FakeExecutor { HueHttpResponse(503, """{"errors":[{"description":"down","type":901}]}""") }
            val result = HueApi(HOST, KEY, executor).setOn(HueCommandTarget.Light("light-1"), on = true)

            assertTrue(result is HueResult.Failure)
            assertEquals(1, executor.requests.size)
        }

    @Test fun aWriteThatReturnsA200ErrorEnvelopeIsStillAFailure() =
        runBlocking {
            // The bridge can answer a PUT with HTTP 200 and a v2 error envelope; a refused command must not read as success.
            val executor = FakeExecutor { HueHttpResponse(200, """{"errors":[{"description":"rejected","type":6}],"data":[]}""") }
            val result = HueApi(HOST, KEY, executor).setOn(HueCommandTarget.Light("light-1"), on = true)

            assertEquals(HueResult.Failure("The bridge refused the request (Hue error 6)."), result)
            assertEquals(1, executor.requests.size)
        }

    @Test fun eachPressSendsItsOwnCommandAndNothingElse() =
        runBlocking {
            val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
            val api = HueApi(HOST, KEY, executor)
            api.setOn(HueCommandTarget.Light("light-1"), on = true)
            api.setOn(HueCommandTarget.Light("light-1"), on = false)

            assertEquals(2, executor.requests.size)
            assertEquals(listOf(true, false), executor.requests.map { JSONObject(it.bodyText()).getJSONObject("on").getBoolean("on") })
        }

    @Test fun theThreeLightingPresetsSendTheirExactBodiesAndUrlsOneRequestEach() =
        runBlocking {
            val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
            val api = HueApi(HOST, KEY, executor)
            val room = HueCommandTarget.Group("grouped-2")

            assertEquals(HueResult.Ok(Unit), api.setOnWithBrightness(room, 100)) // Bright: on at 100%
            assertEquals(HueResult.Ok(Unit), api.setOnWithBrightness(room, 20)) // Dim: on at 20%
            assertEquals(HueResult.Ok(Unit), api.setOn(room, on = false)) // Off: on alone

            // One press is exactly one execute(): three presses, three requests, all PUT to the grouped light.
            assertEquals(3, executor.requests.size)
            assertEquals(listOf("PUT", "PUT", "PUT"), executor.requests.map { it.method })
            assertEquals(
                List(3) { "https://$HOST/clip/v2/resource/grouped_light/grouped-2" },
                executor.requests.map { it.url.toString() },
            )
            // Exact bodies; re-serialised because the JVM org.json reorders keys, so key order is not asserted.
            assertEquals(
                listOf(PRESET_BODY_100, PRESET_BODY_20, PRESET_BODY_OFF),
                executor.requests.map { JSONObject(it.bodyText()).toString() },
            )
        }

    @Test fun anOutOfRangePresetBrightnessFailsWithoutTouchingTheBridge() =
        runBlocking {
            val executor = FakeExecutor { HueHttpResponse(200, EMPTY_ENVELOPE) }
            val api = HueApi(HOST, KEY, executor)
            val room = HueCommandTarget.Group("grouped-2")

            assertEquals(HueResult.Failure(HueErrors.BRIGHTNESS_OUT_OF_RANGE), api.setOnWithBrightness(room, 101))
            assertEquals(HueResult.Failure(HueErrors.BRIGHTNESS_OUT_OF_RANGE), api.setOnWithBrightness(room, -1))
            assertTrue(executor.requests.isEmpty())
        }

    @Test fun aFailedPresetCommandIsSentOnceAndNeverReplayed() =
        runBlocking {
            val executor = FakeExecutor { HueHttpResponse(503, """{"errors":[{"description":"down","type":901}]}""") }
            val result = HueApi(HOST, KEY, executor).setOnWithBrightness(HueCommandTarget.Group("grouped-2"), 20)

            assertTrue(result is HueResult.Failure)
            assertEquals(1, executor.requests.size)
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

        /** The exact bodies the three presets must send, re-serialised by the JVM JSONObject so key order cannot fail them. */
        val PRESET_BODY_100 = JSONObject("""{"on":{"on":true},"dimming":{"brightness":100}}""").toString()
        val PRESET_BODY_20 = JSONObject("""{"on":{"on":true},"dimming":{"brightness":20}}""").toString()
        val PRESET_BODY_OFF = JSONObject("""{"on":{"on":false}}""").toString()
    }
}
