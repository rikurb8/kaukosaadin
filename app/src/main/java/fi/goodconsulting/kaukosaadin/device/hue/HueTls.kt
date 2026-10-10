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
 * There is no trust-all path. [HueTrustManager] always enforces an existing pin. Without a pin,
 * platform trust runs first; only its rejection reaches trust-on-first-use pinning.
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
     * An unpinned CA-signed bridge certificate must also match the address. For a pinned bridge,
     * the stored SPKI pin — already enforced by [HueTrustManager] — is the identity when the
     * certificate has no matching SAN. A connection needs either a matching hostname or pin.
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
