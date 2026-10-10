package fi.goodconsulting.kaukosaadin.device.hue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** The status and body of one finished bridge request. */
internal data class HueHttpResponse(
    val status: Int,
    val body: String,
)

/**
 * The one HTTP call [HueApi] makes. The real binding runs OkHttp; tests bind it to synthetic payloads
 * with no socket, which is where the request shapes and the failed-command rules are checked.
 */
internal fun interface HueRequestExecutor {
    fun execute(request: Request): HueHttpResponse
}

/** Runs each bridge request on OkHttp without transparent retries, so no command is ever replayed. */
internal class OkHttpHueExecutor(
    private val client: OkHttpClient,
) : HueRequestExecutor {
    override fun execute(request: Request): HueHttpResponse =
        client.newCall(request).execute().use { response ->
            HueHttpResponse(response.code, response.body?.string().orEmpty())
        }
}

/** A resource a command can address: one light, or the grouped light that carries a room's or zone's state. */
internal sealed interface HueCommandTarget {
    val id: String

    /** The v2 path segment this target writes to, e.g. `light/<id>`. */
    val path: String

    data class Light(
        override val id: String,
    ) : HueCommandTarget {
        override val path: String get() = "${HueProtocol.LIGHT_RESOURCE}/$id"
    }

    data class Group(
        override val id: String,
    ) : HueCommandTarget {
        override val path: String get() = "${HueProtocol.GROUPED_LIGHT_RESOURCE}/$id"
    }
}

/** The one 0–100 brightness scale: the API refuses values outside it and the slider spans exactly it. */
internal object HueBrightness {
    const val MIN = 0
    const val MAX = 100
}

/** The exact JSON bodies the supported commands send. Nothing else is written to a light. */
internal object HueCommands {
    /** `{"on":{"on":true}}` or `{"on":{"on":false}}`. */
    fun onBody(on: Boolean): String = JSONObject().put("on", JSONObject().put("on", on)).toString()

    /**
     * Brightness alone: `{"dimming":{"brightness":N}}`, with no `on` field. The spec requires that
     * adjusting brightness never turns a light on, and the research notes leave "does writing only
     * dimming.brightness to an off light turn it on?" UNVERIFIED-UNTIL-PHYSICAL-BRIDGE. This is the
     * one place that assumption lives: the screen disables brightness while off, and if a physical
     * bridge turns the light on anyway, this body is the only thing that changes.
     */
    fun brightnessBody(brightness: Int): String = JSONObject().put("dimming", JSONObject().put("brightness", brightness)).toString()

    /**
     * On plus brightness in one request: `{"on":{"on":true},"dimming":{"brightness":N}}`. The
     * lighting presets write both fields together, so Bright and Dim turn the light on explicitly
     * instead of relying on what a brightness-only write does to an off light.
     */
    fun onWithBrightnessBody(brightness: Int): String =
        JSONObject()
            .put("on", JSONObject().put("on", true))
            .put("dimming", JSONObject().put("brightness", brightness))
            .toString()
}

/**
 * The Hue Bridge's lighting API: reads lights, rooms, zones and grouped lights, and sends on/off and
 * brightness commands. Every call returns a [HueResult]; a failure is a value, and a failed command
 * is sent exactly once and dropped, never queued or replayed.
 */
internal class HueApi(
    private val host: String,
    private val applicationKey: String,
    private val executor: HueRequestExecutor,
) {
    suspend fun lights(): HueResult<List<HueLight>> = read(HueProtocol.LIGHT_RESOURCE, HueResources::lights)

    suspend fun rooms(): HueResult<List<HueGroup>> = read(HueProtocol.ROOM_RESOURCE, HueResources::rooms)

    suspend fun zones(): HueResult<List<HueGroup>> = read(HueProtocol.ZONE_RESOURCE, HueResources::zones)

    suspend fun groupedLights(): HueResult<List<HueGroupedLight>> = read(HueProtocol.GROUPED_LIGHT_RESOURCE, HueResources::groupedLights)

    suspend fun setOn(
        target: HueCommandTarget,
        on: Boolean,
    ): HueResult<Unit> = write(target, HueCommands.onBody(on))

    /** Sends brightness only; out-of-range values fail without touching the bridge. See [HueCommands]. */
    suspend fun setBrightness(
        target: HueCommandTarget,
        brightness: Int,
    ): HueResult<Unit> {
        if (brightness !in HueBrightness.MIN..HueBrightness.MAX) return HueResult.Failure(HueErrors.BRIGHTNESS_OUT_OF_RANGE)
        return write(target, HueCommands.brightnessBody(brightness))
    }

    /**
     * Turns [target] on at [brightness] in one request; a preset is one command, not an on followed
     * by a brightness. Out-of-range values fail without touching the bridge, like [setBrightness].
     */
    suspend fun setOnWithBrightness(
        target: HueCommandTarget,
        brightness: Int,
    ): HueResult<Unit> {
        if (brightness !in HueBrightness.MIN..HueBrightness.MAX) return HueResult.Failure(HueErrors.BRIGHTNESS_OUT_OF_RANGE)
        return write(target, HueCommands.onWithBrightnessBody(brightness))
    }

    private suspend fun <T> read(
        resource: String,
        decode: (HueEnvelope) -> List<T>,
    ): HueResult<List<T>> =
        guarded {
            val request =
                Request
                    .Builder()
                    .url("https://$host${HueProtocol.RESOURCE_PATH}/$resource")
                    .header(HueProtocol.API_KEY_HEADER, applicationKey)
                    .get()
                    .build()
            val response = executor.execute(request)
            if (response.status != HTTP_OK) {
                HueResult.Failure(HueErrors.message(response.status, response.body))
            } else {
                decodeEnvelope(response.body, decode)
            }
        }

    private suspend fun write(
        target: HueCommandTarget,
        body: String,
    ): HueResult<Unit> =
        guarded {
            val request =
                Request
                    .Builder()
                    .url("https://$host${HueProtocol.RESOURCE_PATH}/${target.path}")
                    .header(HueProtocol.API_KEY_HEADER, applicationKey)
                    .put(body.toRequestBody(JSON))
                    .build()
            // Exactly one execute() call: a failure is reported once and dropped.
            val response = executor.execute(request)
            if (response.status !in HTTP_OK..HTTP_SUCCESS_MAX) {
                HueResult.Failure(HueErrors.message(response.status, response.body))
            } else {
                decodeEnvelope(response.body) { Unit }
            }
        }

    private fun <T> decodeEnvelope(
        body: String,
        decode: (HueEnvelope) -> T,
    ): HueResult<T> {
        val envelope = HueEnvelope.parse(body) ?: return HueResult.Failure(HueErrors.UNREADABLE)
        return if (!envelope.hasErrors) {
            HueResult.Ok(decode(envelope))
        } else {
            HueResult.Failure(HueErrors.message(HTTP_OK, body))
        }
    }

    // Boundary for every call: blocking I/O off the main thread, and faults become a Failure value.
    @Suppress("TooGenericExceptionCaught") // This is the single catch-all boundary for the whole API.
    private suspend fun <T> guarded(block: () -> HueResult<T>): HueResult<T> =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HueResult.Failure(HueErrors.transportFailure(e))
            }
        }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_SUCCESS_MAX = 299

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * The lighting API for a paired [bridge], or null while it has no stored app key. Its client
         * disables transparent retries so a failed command cannot be replayed by OkHttp either.
         */
        fun of(bridge: HueClient): HueApi? {
            val host = bridge.host
            val key = bridge.applicationKey
            if (host == null || key == null) return null
            return HueApi(host, key, OkHttpHueExecutor(bridge.http(host) { retryOnConnectionFailure(false) }))
        }
    }
}
