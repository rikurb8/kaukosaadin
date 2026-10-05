// LG webOS protocol constants plus the IPv4/MAC validation bounds those packets must satisfy.
@file:Suppress("MagicNumber")

package fi.goodconsulting.kaukosaadin.device

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object LgProtocol {
    enum class Action(
        val key: String?,
    ) {
        Wake(null),
        Up("UP"),
        Down("DOWN"),
        Left("LEFT"),
        Right("RIGHT"),
        Select("ENTER"),
        Back("BACK"),
    }

    fun ipv4(value: String): String {
        val parts = value.split('.')
        require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 }) {
            "Enter a numeric IPv4 address, not a URL."
        }
        require(parts[0].toInt() in 1..223 && parts[0] != "127" && value != "169.254.169.254") {
            "Use the TV's LAN address."
        }
        return value
    }

    fun pairingFingerprint(
        address: String,
        pin: String,
    ): String {
        ipv4(address)
        require(pin.matches(Regex("[0-9a-fA-F]{64}"))) { "Trust the TV certificate first: Device settings › Re-pair." }
        return pin.lowercase()
    }

    fun magicPacket(mac: String): ByteArray {
        require(mac.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) { "Enter MAC as AA:BB:CC:DD:EE:FF." }
        val bytes = mac.split(':').map { it.toInt(16).toByte() }
        require(bytes[0].toInt() and 1 == 0 && bytes.any { it != 0.toByte() }) { "Use the TV's unicast MAC." }
        return ByteArray(102) { if (it < 6) 0xff.toByte() else bytes[(it - 6) % 6] }
    }

    fun button(action: Action): String {
        require(action.key != null) { "Wake uses UDP, not navigation." }
        return "type:button\nname:${action.key}\n\n"
    }

    fun pointerUrl(
        value: String,
        host: String,
    ): String {
        val uri = URI(value)
        require(
            uri.scheme == "wss" &&
                uri.host == host &&
                uri.port == 3001 &&
                uri.userInfo == null &&
                uri.fragment == null &&
                !uri.rawPath.isNullOrEmpty(),
        ) {
            "TV returned an unsafe pointer URL; no cleartext fallback is allowed."
        }
        return value
    }

    fun registration(key: String?): String =
        JSONObject()
            .put("id", "register")
            .put("type", "register")
            .put(
                "payload",
                JSONObject()
                    .put("pairingType", "PIN")
                    .put("forcePairing", false)
                    .put(
                        "manifest",
                        JSONObject()
                            .put("manifestVersion", 1)
                            .put("appVersion", "0.1")
                            .put("permissions", JSONArray(listOf("CONTROL_MOUSE_AND_KEYBOARD"))),
                    ).apply { if (key != null) put("client-key", key) },
            ).toString()

    fun pinRequest(pin: String): String {
        require(pin.matches(Regex("[0-9]{4,8}"))) { "Enter the TV's 4–8 digit PIN, including leading zeros." }
        return JSONObject()
            .put("id", "pair-pin")
            .put("type", "request")
            .put("uri", "ssap://pairing/setPin")
            .put("payload", JSONObject().put("pin", pin))
            .toString()
    }

    fun needsPin(
        reply: JSONObject,
        navigation: Boolean,
    ): Boolean {
        val method = reply.optJSONObject("payload")?.optString("pairingType")?.takeIf { it.isNotEmpty() }
        if (reply.optString("type") == "registered" || method == null) return false
        check(!navigation) { "TV needs pairing. Command discarded; use Device settings › Re-pair, then press again." }
        check(method.equals("PIN", ignoreCase = true)) {
            "TV did not offer PIN pairing. Check TV firmware/network-remote settings; no PROMPT fallback was used."
        }
        return true
    }

    fun response(raw: String): JSONObject {
        val json = JSONObject(raw)
        check(json.optString("type") != "error") {
            "TV rejected the request or PIN. Retry Connect with a new TV code; forget pairing if saved credentials were revoked."
        }
        check(json.optJSONObject("payload")?.optBoolean("returnValue", true) != false) {
            "TV refused the request. Reconnect or forget pairing."
        }
        return json
    }
}
