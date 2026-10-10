package fi.goodconsulting.kaukosaadin.device.hue

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** The bridge's SPKI SHA-256 pin: hashing the public key rather than the whole cert survives re-issues. */
internal object HuePin {
    fun spkiSha256(certificate: X509Certificate): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(certificate.publicKey.encoded)
            .joinToString("") { "%02x".format(it) }

    fun matches(
        pinned: String?,
        certificate: X509Certificate?,
    ): Boolean {
        if (pinned.isNullOrBlank() || certificate == null) return false
        return pinned.equals(spkiSha256(certificate), ignoreCase = true)
    }
}

/**
 * Verifies the leaf certificate of one Hue Bridge.
 *
 * An existing pin always identifies the bridge, even when the platform accepts its chain. Without
 * a pin, a properly CA-signed certificate verifies as ordinary HTTPS. If the platform rejects the
 * chain, [recordTrust] persists the presented public key on first use; every later connection must
 * present the same key.
 *
 * RESIDUAL RISK: trust-on-first-use accepts whatever certificate the bridge presents during that one
 * pairing window, so an active attacker already on the LAN at that moment can substitute their own
 * key. All later connections are pinned, so the exposure is that single window. A factory reset or a
 * bridge keypair rotation changes the key and fails the pin; the bridge must then be re-paired.
 */
internal class HueTrustManager(
    private val system: X509TrustManager,
    private val storedPin: () -> String?,
    private val recordTrust: (String) -> Unit,
) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ): Unit = throw CertificateException("The bridge is never the TLS client.")

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {
        if (chain.isEmpty()) throw CertificateException("The bridge presented no certificate.")
        val pinned = storedPin()
        if (!pinned.isNullOrBlank()) {
            if (!HuePin.matches(pinned, chain[0])) {
                throw CertificateException("The bridge's certificate changed. Forget and pair it again to re-trust it.")
            }
            return
        }
        if (acceptedBySystem(chain, authType)) return
        recordTrust(HuePin.spkiSha256(chain[0]))
    }

    private fun acceptedBySystem(
        chain: Array<out X509Certificate>,
        authType: String,
    ): Boolean =
        try {
            system.checkServerTrusted(chain, authType)
            true
        } catch (_: CertificateException) {
            false
        }
}
