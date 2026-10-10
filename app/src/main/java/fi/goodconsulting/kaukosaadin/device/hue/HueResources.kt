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
    /** The device that owns this light (`owner.rid`); a room lists its members by device, so this is how a light finds its room. */
    val ownerId: String? = null,
)

/** One grouped light: `on` is true when any light is on; brightness averages the on lights only. */
internal data class HueGroupedLight(
    val id: String,
    val on: Boolean,
    val brightness: Double?,
)

/**
 * One room or zone; [groupedLightId] is the `grouped_light` service that carries its on/brightness.
 * The bridge reports rooms and zones as separate resources with the same shape, so one type carries
 * both.
 */
internal data class HueGroup(
    val id: String,
    val name: String,
    val groupedLightId: String?,
    /** The ids in `children[]`: devices for a room, lights for a zone. [contains] matches a light against either. */
    val memberIds: Set<String> = emptySet(),
) {
    /** Whether [light] belongs here: named directly, as a zone does, or through its owning device, as a room does. */
    fun contains(light: HueLight): Boolean = light.id in memberIds || light.ownerId in memberIds
}

/** The v2 response envelope: `{"errors":[{"description","type"}],"data":[...]}`. */
internal class HueEnvelope(
    val data: JSONArray,
    /** Any nonempty errors array is a refusal, even when its entries carry no numeric type. */
    val hasErrors: Boolean,
    /** The numeric error types the envelope carries; empty when none are provided. The description is not kept. */
    val errorTypes: List<Int>,
) {
    companion object {
        /** Parses [body] as a v2 envelope; null when its required errors array or optional data array is unreadable. */
        fun parse(body: String): HueEnvelope? =
            try {
                val json = JSONObject(body)
                val errors = json.getJSONArray("errors")
                HueEnvelope(
                    data = if (json.has("data")) json.getJSONArray("data") else JSONArray(),
                    hasErrors = errors.length() > 0,
                    errorTypes = errorTypes(errors),
                )
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
                on = HueState.on(resource) ?: false,
                brightness = HueState.brightness(resource),
                ownerId = resource.optJSONObject("owner")?.optString("rid")?.takeIf { it.isNotBlank() },
            )
        }

    fun rooms(envelope: HueEnvelope): List<HueGroup> = groups(envelope, HueProtocol.ROOM_RESOURCE)

    fun zones(envelope: HueEnvelope): List<HueGroup> = groups(envelope, HueProtocol.ZONE_RESOURCE)

    fun groupedLights(envelope: HueEnvelope): List<HueGroupedLight> =
        decode(envelope, HueProtocol.GROUPED_LIGHT_RESOURCE) { resource ->
            HueGroupedLight(
                id = resource.optString("id"),
                on = HueState.on(resource) ?: false,
                brightness = HueState.brightness(resource),
            )
        }

    // A room and a zone carry the same fields; only the resource type differs.
    private fun groups(
        envelope: HueEnvelope,
        resource: String,
    ): List<HueGroup> = decode(envelope, resource, ::group)

    private fun group(resource: JSONObject): HueGroup =
        HueGroup(
            id = resource.optString("id"),
            name = name(resource),
            groupedLightId = groupedLightId(resource),
            memberIds = memberIds(resource),
        )

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

    /** The human name sits on `metadata.name`; a resource missing it falls back to its id, never to blank. */
    private fun name(resource: JSONObject): String =
        resource
            .optJSONObject("metadata")
            ?.optString("name")
            .orEmpty()
            .trim()
            .ifBlank { resource.optString("id") }

    /** Every `rid` in `children[]`, whatever its `rtype`; blank ones are dropped. */
    private fun memberIds(resource: JSONObject): Set<String> {
        val children = resource.optJSONArray("children") ?: return emptySet()
        return (0 until children.length())
            .mapNotNull { children.optJSONObject(it)?.optString("rid")?.takeIf { rid -> rid.isNotBlank() } }
            .toSet()
    }

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
