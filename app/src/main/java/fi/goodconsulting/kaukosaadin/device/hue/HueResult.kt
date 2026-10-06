package fi.goodconsulting.kaukosaadin.device.hue

import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * The outcome of one bridge call: a value the UI can place on screen. A read or a command never
 * throws to its caller, so a bridge or light that is down is a [Failure] the screen renders, not a
 * crash. A failed command is returned once and dropped; nothing retries or replays it.
 */
internal sealed interface HueResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : HueResult<T>

    data class Failure(
        val message: String,
    ) : HueResult<Nothing>
}

/**
 * The app's own failure text for the bridge. The v2 envelope carries a peer-controlled `description`;
 * per this module's convention that text is parsed but never shown, and the message is keyed on the
 * numeric `type` (or the HTTP status) instead.
 */
internal object HueErrors {
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val NOT_FOUND = 404
    private const val TOO_MANY_REQUESTS = 429
    private const val SERVER_ERROR_MIN = 500
    private const val SERVER_ERROR_MAX = 599

    /** Turns an HTTP status and its v2 `{"errors":[{"description","type"}]}` body into shown text. */
    fun message(
        status: Int,
        body: String,
    ): String {
        val types = HueEnvelope.parse(body)?.errorTypes.orEmpty()
        if (types.isNotEmpty()) return "The bridge refused the request (Hue error ${types.first()})."
        return when (status) {
            UNAUTHORIZED -> "The bridge rejected the app key. Pair the bridge again."
            FORBIDDEN -> "The bridge refused this request."
            NOT_FOUND -> "The bridge no longer has that light, room or group."
            TOO_MANY_REQUESTS -> "The bridge is rate-limiting requests. Try again in a moment."
            in SERVER_ERROR_MIN..SERVER_ERROR_MAX -> "The bridge reported an internal error ($status)."
            else -> "The bridge refused the request ($status)."
        }
    }

    fun streamRefused(status: Int): String = "The bridge refused the event stream ($status)."

    /** Our own text for a reply that is not a v2 envelope at all. */
    const val UNREADABLE: String = "Unexpected reply from the Hue bridge."

    /** Our own text for a bridge or light that cannot be reached at all. */
    const val UNREACHABLE: String = "Hue bridge unreachable. Check the Wi-Fi/LAN and that the bridge is on."

    /** Our own text for a certificate the app does not (or no longer) trust. */
    const val TRUST_CHANGED: String = "The bridge's certificate changed or was rejected. Forget the bridge and pair it again."

    /** Our own text for an out-of-range slider value, so a UI bug can never send a bad command. */
    const val BRIGHTNESS_OUT_OF_RANGE: String = "Brightness must be 0–100."

    /** Maps a stream/command transport fault onto a screen message; never repeats raw exception text. */
    fun transportFailure(e: Exception): String =
        when (e) {
            is HueStreamException -> e.message ?: UNREADABLE
            is CertificateException, is SSLException -> TRUST_CHANGED
            else -> UNREACHABLE
        }
}
