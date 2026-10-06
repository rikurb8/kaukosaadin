// The v2 stream's JSON keys and SSE framing describe the protocol line-for-line.
@file:Suppress("MagicNumber")

package fi.goodconsulting.kaukosaadin.device.hue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One change the bridge reported for a resource the lighting screen tracks. */
internal data class HueEvent(
    val resourceId: String,
    val resourceType: String,
    /** The changed `on.on`, when the event carries it. */
    val on: Boolean?,
    /** The changed `dimming.brightness`, when the event carries it. */
    val brightness: Double?,
)

/** Decodes one SSE frame's `data`, an array of `{type,data:[resource,...]}` events. */
internal object HueEvents {
    /** A malformed frame yields no events; one bad frame must not break the live stream. */
    fun decode(data: String): List<HueEvent> =
        try {
            val events = JSONArray(data)
            (0 until events.length()).flatMap { index -> decodeEvent(events.optJSONObject(index)) }
        } catch (_: JSONException) {
            emptyList()
        }

    private fun decodeEvent(event: JSONObject?): List<HueEvent> {
        val resources = event?.optJSONArray("data") ?: return emptyList()
        return (0 until resources.length()).mapNotNull { index ->
            val resource = resources.optJSONObject(index) ?: return@mapNotNull null
            val id = resource.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = resource.optString("type").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            HueEvent(resourceId = id, resourceType = type, on = HueState.on(resource), brightness = HueState.brightness(resource))
        }
    }
}

/** One server-sent event frame: the optional `id` (the resume cursor) and the joined `data` payload. */
internal data class SseFrame(
    val id: String?,
    val data: String,
)

/**
 * Hand-rolled Server-Sent Events framing over a streaming source. `okhttp-sse` is deliberately not a
 * dependency, so frames are split on a blank line and `id:`/`data:` lines are read directly.
 */
internal class SseReader(
    private val source: BufferedSource,
) {
    /** Reads one frame, or null once the stream ends. A partial trailing frame is discarded. */
    fun readFrame(): SseFrame? {
        var id: String? = null
        var data: String? = null
        while (true) {
            val line = source.readUtf8Line() ?: return null
            if (line.isEmpty()) {
                if (id == null && data == null) continue // stray blank lines between frames
                return SseFrame(id, data.orEmpty())
            }
            when {
                line.startsWith(COMMENT_FIELD) -> Unit
                line.startsWith(ID_FIELD) -> id = line.substring(ID_FIELD.length).trim()
                line.startsWith(DATA_FIELD) -> data = appendData(data, line.substring(DATA_FIELD.length).trim())
            }
        }
    }

    private fun appendData(
        current: String?,
        value: String,
    ): String = if (current == null) value else "$current\n$value"

    private companion object {
        const val COMMENT_FIELD = ":"
        const val ID_FIELD = "id:"
        const val DATA_FIELD = "data:"
    }
}

/** A step of the live subscription: the bridge accepting it, then each decoded frame. */
internal sealed interface HueStreamEvent {
    /** The bridge opened the subscription (HTTP 200); frames follow. */
    data object Open : HueStreamEvent

    /** One SSE frame; [eventId] is the resume cursor for the next `If-None-Match`. */
    data class Frame(
        val eventId: String?,
        val events: List<HueEvent>,
    ) : HueStreamEvent
}

/** The live HTTP read the event stream needs; tests bind it to a pipe with no socket. */
internal interface HueStreamTransport {
    /** Opens [request] on the calling thread and returns the live response. */
    fun open(request: Request): HueStreamed
}

/** A streaming HTTP response; [close] tears the socket down and unblocks a pending read. */
internal interface HueStreamed : Closeable {
    val status: Int

    val source: BufferedSource
}

/** The bridge refused to open the event stream; carries the app's own text so it is safe to show. */
internal class HueStreamException(
    val reason: String,
) : IOException(reason)

/** The real binding: an OkHttp GET whose body is read as a long-lived stream, with no read timeout. */
internal class OkHttpHueStreamer(
    private val client: OkHttpClient,
) : HueStreamTransport {
    override fun open(request: Request): HueStreamed {
        val response = client.newCall(request).execute()
        val body = response.body
        if (body == null) {
            response.close()
            throw IOException("The bridge returned no event stream body.")
        }
        return object : HueStreamed {
            override val status: Int = response.code
            override val source: BufferedSource = body.source()

            override fun close() = response.close()
        }
    }
}

/**
 * Subscribes to the bridge's v2 event stream. The flow is cold: it opens one long-lived GET and closes
 * it when the collector is cancelled. [fromEventId] resumes with `If-None-Match: <id>` as the notes
 * describe. A transport failure throws; [HueConnection] turns that into a value the screen can show.
 */
internal class HueEventStream(
    private val host: String,
    private val applicationKey: String,
    private val transport: HueStreamTransport,
) {
    fun updates(fromEventId: String? = null): Flow<HueStreamEvent> =
        flow {
            val streamed = transport.open(request(fromEventId))
            val collector = this
            try {
                coroutineScope {
                    // The read below blocks (OkHttp's socket read / okio), so cancelling this
                    // coroutine cannot unblock it and reach completion on its own: it parks in
                    // "cancelling" while still blocked. A sibling parked on awaitCancellation closes
                    // the stream in its finally the instant the collector is cancelled, which is what
                    // unblocks the read; the outer finally is the ordinary-path close.
                    val closeOnCancel =
                        launch {
                            try {
                                awaitCancellation()
                            } finally {
                                streamed.close()
                            }
                        }
                    try {
                        if (streamed.status != HTTP_OK) throw HueStreamException(HueErrors.streamRefused(streamed.status))
                        collector.emit(HueStreamEvent.Open)
                        collector.emitFrames(streamed)
                    } finally {
                        closeOnCancel.cancel()
                    }
                }
            } catch (e: IOException) {
                // Closing the stream to honour cancellation surfaces as an IOException on the blocked
                // read; report that as cancellation, not as a bridge failure.
                if (!currentCoroutineContext().isActive) throw CancellationException("Hue event stream closed.", e)
                throw e
            } finally {
                streamed.close()
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<HueStreamEvent>.emitFrames(streamed: HueStreamed) {
        val reader = SseReader(streamed.source)
        var frame = reader.readFrame()
        while (frame != null) {
            if (frame.data.isNotBlank()) {
                val events = HueEvents.decode(frame.data)
                if (events.isNotEmpty()) emit(HueStreamEvent.Frame(frame.id, events))
            }
            frame = reader.readFrame()
        }
    }

    private fun request(fromEventId: String?): Request =
        Request
            .Builder()
            .url("https://$host${HueProtocol.EVENT_STREAM_PATH}")
            .header(HueProtocol.API_KEY_HEADER, applicationKey)
            .header(ACCEPT_HEADER, ACCEPT_EVENT_STREAM)
            .apply { fromEventId?.takeIf { it.isNotBlank() }?.let { header(IF_NONE_MATCH_HEADER, it) } }
            .get()
            .build()

    companion object {
        private const val HTTP_OK = 200
        private const val ACCEPT_HEADER = "Accept"
        private const val ACCEPT_EVENT_STREAM = "text/event-stream"
        private const val IF_NONE_MATCH_HEADER = "If-None-Match"

        /** A streaming read stays open between events, so the transport's 5 s read timeout is removed. */
        private const val STREAM_READ_TIMEOUT_MS = 0L

        /** The event-stream reader for a paired [bridge], or null while it has no stored app key. */
        fun of(bridge: HueClient): HueEventStream? {
            val host = bridge.host
            val key = bridge.applicationKey
            if (host == null || key == null) return null
            val client =
                bridge.http(host) {
                    readTimeout(STREAM_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    retryOnConnectionFailure(false)
                }
            return HueEventStream(host, key, OkHttpHueStreamer(client))
        }
    }
}
