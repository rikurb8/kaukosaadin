package fi.goodconsulting.kaukosaadin.device.hue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import okhttp3.Request
import okio.Buffer
import okio.BufferedSource
import okio.Pipe
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HueEventStreamTest {
    @Test fun updatesEmitsOpenThenTheDecodedFrames() = runBlocking {
        val transport = FakeStreamTransport { streamed(200, FRAME_ONE, FRAME_TWO) }
        val updates = withTimeout(TIMEOUT_MS) { HueEventStream(HOST, KEY, transport).updates().toList() }

        assertEquals(HueStreamEvent.Open, updates[0])
        assertEquals(
            HueStreamEvent.Frame("1:0", listOf(HueEvent("update", "light-1", "light", on = true, brightness = null))),
            updates[1],
        )
        assertEquals(
            HueStreamEvent.Frame("2:0", listOf(HueEvent("update", "light-2", "light", on = null, brightness = 25.0))),
            updates[2],
        )
    }

    @Test fun updatesRequestsTheEventStreamAcceptingSse() = runBlocking {
        val transport = FakeStreamTransport { streamed(200) }
        withTimeout(TIMEOUT_MS) { HueEventStream(HOST, KEY, transport).updates().toList() }

        val request = transport.requests.single()
        assertEquals("https://$HOST/eventstream/clip/v2", request.url.toString())
        assertEquals("text/event-stream", request.header("Accept"))
        assertEquals(KEY, request.header(HueProtocol.API_KEY_HEADER))
        assertNull(request.header("If-None-Match"))
    }

    @Test fun updatesResumesWithIfNoneMatch() = runBlocking {
        val transport = FakeStreamTransport { streamed(200) }
        withTimeout(TIMEOUT_MS) { HueEventStream(HOST, KEY, transport).updates("1690000000:0").toList() }

        assertEquals("1690000000:0", transport.requests.single().header("If-None-Match"))
    }

    @Test fun updatesThrowsWhenTheBridgeRefuses() = runBlocking {
        val transport = FakeStreamTransport { streamed(401) }
        val error = runCatching { withTimeout(TIMEOUT_MS) { HueEventStream(HOST, KEY, transport).updates().toList() } }.exceptionOrNull()
        assertTrue(error is HueStreamException)
    }

    @Test fun cancellingTheCollectorClosesTheStream() = runBlocking {
        val pipe = Pipe(1_024)
        val closed = CompletableDeferred<Unit>()
        val streamed =
            object : HueStreamed {
                override val status: Int = 200
                override val source: BufferedSource = pipe.source.buffer()

                override fun close() {
                    runCatching { pipe.sink.close() }
                    runCatching { pipe.source.close() }
                    closed.complete(Unit)
                }
            }
        val stream = HueEventStream(HOST, KEY, FakeStreamTransport { streamed })
        val opened = CompletableDeferred<Unit>()
        val job =
            launch {
                stream.updates().collect { if (it == HueStreamEvent.Open) opened.complete(Unit) }
            }

        withTimeout(TIMEOUT_MS) { opened.await() }
        withTimeout(TIMEOUT_MS) { job.cancelAndJoin() }
        assertTrue("the stream must be closed on cancellation", closed.isCompleted)
    }

    private fun streamed(
        status: Int,
        vararg frames: String,
    ): HueStreamed =
        object : HueStreamed {
            override val status: Int = status
            override val source: BufferedSource = Buffer().writeUtf8(frames.joinToString(""))

            override fun close() = Unit
        }

    private class FakeStreamTransport(
        private val open: () -> HueStreamed,
    ) : HueStreamTransport {
        val requests = mutableListOf<Request>()

        override fun open(request: Request): HueStreamed {
            requests += request
            return open()
        }
    }

    private companion object {
        const val HOST = "192.168.1.42"
        const val KEY = "a4e08834-0893-4013-b646-738582ec15c9"
        const val TIMEOUT_MS = 5_000L

        const val FRAME_ONE =
            "id: 1:0\ndata: [{\"id\":\"e\",\"type\":\"update\",\"creationtime\":\"t\",\"data\":[{\"id\":\"light-1\",\"type\":\"light\",\"on\":{\"on\":true}}]}]\n\n"
        const val FRAME_TWO =
            "id: 2:0\ndata: [{\"id\":\"e\",\"type\":\"update\",\"creationtime\":\"t\",\"data\":[{\"id\":\"light-2\",\"type\":\"light\",\"dimming\":{\"brightness\":25.0}}]}]\n\n"
    }
}
