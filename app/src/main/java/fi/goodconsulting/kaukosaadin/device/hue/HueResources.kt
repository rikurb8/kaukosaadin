// Hue's v2 resource types and the 0–100 brightness scale are protocol vocabulary, not layout literals.
@file:Suppress("MagicNumber")

package fi.goodconsulting.kaukosaadin.device.hue

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One light the bridge reports: its identity, display name, and current on/brightness state. */
internal data class HueLight(
    val id: String,
    val name: String,
    val on: Boolean,
    /** 0–100, or null when the light reports no dimming value. */
    val brightness: Double?,
)

/** One room's grouped light: `on` is true when any light is on; brightness averages the on lights only. */
internal data class HueGroupedLight(
    val id: String,
    val on: Boolean,
    val brightness: Double?,
)

/** One room; [groupedLightId] is the `grouped_light` service that carries the room's on/brightness. */
internal data class HueRoom(
    val id: String,
    val name: String,
    val groupedLightId: String?,
)

/** The v2 response envelope: `{"errors":[{"description","type"}],"data":[...]}`. */
internal class HueEnvelope(
    val data: JSONArray,
    /** The numeric error types the envelope carries; empty on success. The description is not kept. */
    val errorTypes: List<Int>,
) {
    companion object {
        /** Parses [body] as a v2 envelope; null when it is not a JSON object at all. */
        fun parse(body: String): HueEnvelope? =
            try {
                val json = JSONObject(body)
                HueEnvelope(data = json.optJSONArray("data") ?: JSONArray(), errorTypes = errorTypes(json.optJSONArray("errors")))
            } catch (_: JSONException) {
                null
            }

        private fun errorTypes(errors: JSONArray?): List<Int> {
            if (errors == null) return emptyList()
            return (0 until errors.length()).mapNotNull { index ->
                errors.optJSONObject(index)?.takeIf { it.has("type") }?.optInt("type")
            }
        }
    }
}

/** Decodes the v2 resource envelopes the lighting client reads into the small model the UI uses. */
internal object HueResources {
    fun lights(envelope: HueEnvelope): List<HueLight> =
        decode(envelope, HueProtocol.LIGHT_RESOURCE) { resource ->
            HueLight(
                id = resource.optString("id"),
                name = name(resource),
                on = on(resource) ?: false,
                brightness = brightness(resource),
            )
        }

    fun rooms(envelope: HueEnvelope): List<HueRoom> =
        decode(envelope, HueProtocol.ROOM_RESOURCE) { resource ->
            HueRoom(
                id = resource.optString("id"),
                name = name(resource),
                groupedLightId = groupedLightId(resource),
            )
        }

    fun groupedLights(envelope: HueEnvelope): List<HueGroupedLight> =
        decode(envelope, HueProtocol.GROUPED_LIGHT_RESOURCE) { resource ->
            HueGroupedLight(
                id = resource.optString("id"),
                on = on(resource) ?: false,
                brightness = brightness(resource),
            )
        }

    // The list endpoint is already filtered by type; entries without a matching type or an id are junk.
    private fun <T> decode(
        envelope: HueEnvelope,
        type: String,
        build: (JSONObject) -> T,
    ): List<T> {
        val data = envelope.data
        val items = ArrayList<T>(data.length())
        for (index in 0 until data.length()) {
            val resource = data.optJSONObject(index)
            if (resource != null && resource.optString("type") == type && resource.optString("id").isNotBlank()) {
                items += build(resource)
            }
        }
        return items
    }

    /** The human name sits on `metadata.name`; a light missing it falls back to its id, never to blank. */
    private fun name(resource: JSONObject): String =
        resource
            .optJSONObject("metadata")
            ?.optString("name")
            .orEmpty()
            .trim()
            .ifBlank { resource.optString("id") }

    private fun on(resource: JSONObject): Boolean? = resource.optJSONObject("on")?.takeIf { it.has("on") }?.optBoolean("on")

    private fun brightness(resource: JSONObject): Double? =
        resource
            .optJSONObject("dimming")
            ?.takeIf { it.has("brightness") }
            ?.optDouble("brightness")
            ?.takeIf { it.isFinite() }

    /** Found in `services[]` where `rtype == "grouped_light"`; other service types are ignored. */
    private fun groupedLightId(resource: JSONObject): String? {
        val services = resource.optJSONArray("services") ?: return null
        return (0 until services.length())
            .mapNotNull { services.optJSONObject(it) }
            .firstOrNull { it.optString("rtype") == HueProtocol.GROUPED_LIGHT_RESOURCE }
            ?.optString("rid")
            ?.takeIf { it.isNotBlank() }
    }
}
