package fi.goodconsulting.kaukosaadin.device.hue

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The verified HTTPS transport for one bridge. It is the seam ticket #8 builds its API client on:
 * [configure] returns an OkHttp builder that trusts [host] and speaks HTTPS only.
 *
 * There is no trust-all path. The platform trust manager runs first; only its rejection of the
 * bridge's self-signed certificate reaches the trust-on-first-use pin in [HueTrustManager].
 */
internal class HueTls(
    private val credentials: HueCredentials,
) {
    /** A client builder that verifies [host]; the caller tunes timeouts, then builds. */
    fun configure(host: String): OkHttpClient.Builder {
        val trust =
            HueTrustManager(
                system = systemTrustManager(),
                storedPin = { credentials.pin },
                recordTrust = { pin -> checkPinStored(credentials.saveTrust(host, pin)) },
            )
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        return OkHttpClient
            .Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier(hostnameVerifier(credentials))
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
    }

    /**
     * A CA-signed bridge certificate must also match the address, so ordinary HTTPS verification
     * applies. A self-signed bridge on a LAN address carries no matching SAN, and there the stored
     * SPKI pin — already enforced by [HueTrustManager] — is the identity. The default verifier is
     * still consulted first, so no certificate is accepted whose hostname does not check out.
     */
    private fun hostnameVerifier(credentials: HueCredentials): HostnameVerifier =
        HostnameVerifier { host, session ->
            if (defaultHostnameVerifier.verify(host, session)) {
                true
            } else {
                HuePin.matches(credentials.pin, session.peerCertificates.firstOrNull() as? X509Certificate)
            }
        }

    private fun checkPinStored(stored: Boolean) {
        if (!stored) throw CertificateException("Could not store the bridge's certificate pin; pairing was not completed.")
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L

        private val defaultHostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

        private fun systemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: error("The platform has no X.509 trust manager.")
        }
    }
}
