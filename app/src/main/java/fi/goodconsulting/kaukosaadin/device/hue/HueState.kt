package fi.goodconsulting.kaukosaadin.device.hue

import org.json.JSONObject

/**
 * The `on.on` and `dimming.brightness` fields a v2 resource carries. A resource read and a stream
 * event report the same shape, so both decode it here once.
 */
internal object HueState {
    fun on(resource: JSONObject): Boolean? = resource.optJSONObject("on")?.takeIf { it.has("on") }?.optBoolean("on")

    fun brightness(resource: JSONObject): Double? =
        resource
            .optJSONObject("dimming")
            ?.takeIf { it.has("brightness") }
            ?.optDouble("brightness")
            ?.takeIf { it.isFinite() }
}
