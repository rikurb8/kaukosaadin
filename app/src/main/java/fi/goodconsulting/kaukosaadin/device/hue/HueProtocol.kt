// Hue's numeric validation bounds (IPv4 octets, LAN address ranges) are self-describing here.
@file:Suppress("MagicNumber")

package fi.goodconsulting.kaukosaadin.device.hue

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The Hue Bridge's link-button register call. The v2 API still mints its app key through the v1
 * `POST /api` endpoint, whose reply uses the v1 `[{"error":{...}}]` envelope, not v2's `{"errors":[]}`.
 */
object HueProtocol {
    /** The `devicetype` the bridge records for this app: `<app name>#<instance name>`. */
    const val PAIRING_DEVICE_TYPE = "kaukosaadin#android"

    /** Carries the app key on every `/clip/v2` call; ticket #8's client reads this header name. */
    const val API_KEY_HEADER = "hue-application-key"

    /** The unauthenticated endpoint that returns the app key once the link button has been pressed. */
    const val PAIRING_PATH = "/api"

    /** The v2 resource collection, e.g. `https://<bridge>/clip/v2/resource/light`. */
    const val RESOURCE_PATH = "/clip/v2/resource"

    /** The v2 server-sent event stream that reports state changes made by any controller. */
    const val EVENT_STREAM_PATH = "/eventstream/clip/v2"

    /** The resource type of an individual light. */
    const val LIGHT_RESOURCE = "light"

    /** The resource type of a room, whose on/brightness live on its grouped light. */
    const val ROOM_RESOURCE = "room"

    /** The resource type that carries a room's (or bridge home's) aggregated on/brightness. */
    const val GROUPED_LIGHT_RESOURCE = "grouped_light"

    /** The request body that asks the bridge for an app key (and an entertainment client key). */
    fun pairingBody(): String =
        JSONObject()
            .put("devicetype", PAIRING_DEVICE_TYPE)
            .put("generateclientkey", true)
            .toString()

    /** The body of a `POST` to [PAIRING_PATH], parsed from the bridge's reply. */
    fun pairingResult(body: String): HuePairingResult {
        val entry = firstEntry(body) ?: return HuePairingResult.Rejected(UNEXPECTED)
        val success = entry.optJSONObject("success")
        val error = entry.optJSONObject("error")
        return when {
            success != null -> paired(success) ?: HuePairingResult.Rejected(UNEXPECTED)
            error != null -> failure(error)
            else -> HuePairingResult.Rejected(UNEXPECTED)
        }
    }

    /** The `username` is the app key; the key format is unverified, so any non-blank text is kept. */
    private fun paired(success: JSONObject): HuePairingResult.Paired? =
        success
            .optString("username")
            .takeIf { it.isNotBlank() }
            ?.let { HuePairingResult.Paired(it, success.optString("clientkey").takeIf { key -> key.isNotBlank() }) }

    // 101 is the only numeric type the notes confirm; other numbers are reported without repeating
    // the bridge's (peer-controlled) description text.
    private fun failure(error: JSONObject): HuePairingResult =
        when {
            error.optInt("type") == LINK_BUTTON_NOT_PRESSED -> HuePairingResult.LinkButtonNotPressed
            error.has("type") -> HuePairingResult.Rejected("The bridge rejected the pairing request (error ${error.optInt("type")}).")
            else -> HuePairingResult.Rejected(UNEXPECTED)
        }

    /** Validates the address the operator typed or the scan returned; never a URL or a public host. */
    fun ipv4(value: String): String {
        val parts = value.split('.')
        require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 }) {
            "Enter the bridge's numeric IPv4 address, not a URL."
        }
        require(parts[0].toInt() in 1..223 && parts[0] != "127" && value != "169.254.169.254") {
            "Use the bridge's LAN address."
        }
        return value
    }

    private fun firstEntry(body: String): JSONObject? =
        try {
            JSONArray(body).optJSONObject(0)
        } catch (_: JSONException) {
            null
        }

    private const val LINK_BUTTON_NOT_PRESSED = 101
    private const val UNEXPECTED = "Unexpected pairing response from the bridge."
}

/** The outcome of one link-button pairing attempt. */
sealed interface HuePairingResult {
    /** The bridge minted an app key; [clientKey] is present only when the bridge returned one. */
    data class Paired(
        val applicationKey: String,
        val clientKey: String?,
    ) : HuePairingResult

    /** Error type 101: the operator has not pressed the link button yet. */
    data object LinkButtonNotPressed : HuePairingResult

    /** The bridge refused for another reason; [message] never repeats bridge-supplied text. */
    data class Rejected(
        val message: String,
    ) : HuePairingResult
}
