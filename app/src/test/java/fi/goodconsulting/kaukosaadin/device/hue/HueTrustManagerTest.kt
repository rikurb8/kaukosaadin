package fi.goodconsulting.kaukosaadin.device.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

/**
 * The pin is a known answer: [CERTIFICATE_DER] was generated once with
 * `openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -subj "/CN=Philips Hue"`, and
 * [EXPECTED_PIN] is its SPKI SHA-256 from `openssl x509 -pubkey | openssl pkey -pubin -outform DER | openssl dgst -sha256`.
 */
class HueTrustManagerTest {
    private val certificate: X509Certificate by lazy {
        val der = Base64.getMimeDecoder().decode(CERTIFICATE_DER)
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    @Test fun spkiSha256MatchesTheKnownBridgeCertificatePin() {
        assertEquals(EXPECTED_PIN, HuePin.spkiSha256(certificate))
    }

    @Test fun pinComparisonIgnoresHexCaseAndMissingValues() {
        assertTrue(HuePin.matches(EXPECTED_PIN.uppercase(), certificate))
        assertFalse(HuePin.matches(null, certificate))
        assertFalse(HuePin.matches("  ", certificate))
        assertFalse(HuePin.matches("00".repeat(32), certificate))
        assertFalse(HuePin.matches("not hex", certificate))
    }

    @Test fun aChainThePlatformTrustManagerAcceptsNeedsNoPin() {
        val recorded = mutableListOf<String>()
        val trust = HueTrustManager(AcceptingSystemTrust, storedPin = { null }, recordTrust = { recorded += it })
        trust.checkServerTrusted(arrayOf(certificate), "ECDSA")
        assertTrue(recorded.isEmpty())
    }

    @Test fun aRejectedChainWithNoPinIsTrustedOnFirstUseAndRecorded() {
        val recorded = mutableListOf<String>()
        val trust = HueTrustManager(RejectingSystemTrust, storedPin = { null }, recordTrust = { recorded += it })
        trust.checkServerTrusted(arrayOf(certificate), "ECDSA")
        assertEquals(listOf(EXPECTED_PIN), recorded)
    }

    @Test fun aRejectedChainWithTheMatchingPinIsAcceptedAgain() {
        val recorded = mutableListOf<String>()
        val trust = HueTrustManager(RejectingSystemTrust, storedPin = { EXPECTED_PIN }, recordTrust = { recorded += it })
        trust.checkServerTrusted(arrayOf(certificate), "ECDSA")
        assertTrue(recorded.isEmpty())
    }

    @Test fun aRejectedChainWithAChangedPinIsRejected() {
        val trust = HueTrustManager(RejectingSystemTrust, storedPin = { "00".repeat(32) }, recordTrust = {})
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(certificate), "ECDSA") }
    }

    @Test fun anEmptyChainIsAlwaysRejected() {
        assertThrows(CertificateException::class.java) {
            HueTrustManager(AcceptingSystemTrust, storedPin = { null }, recordTrust = {}).checkServerTrusted(emptyArray(), "ECDSA")
        }
    }

    private object AcceptingSystemTrust : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) = Unit
    }

    private object RejectingSystemTrust : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ): Unit = throw CertificateException("client auth is not used")

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ): Unit = throw CertificateException("self-signed")
    }

    private companion object {
        const val EXPECTED_PIN = "e6f9e3e2f8d0f74cd74e6cd703e172f0eecd85d5027d897581288351a8ccbcd7"

        const val CERTIFICATE_DER =
            "MIIBHjCBxAIJAL4BTBJ+4HGWMAoGCCqGSM49BAMCMBYxFDASBgNVBAMMC1BoaWxpcHMgSHVl" +
                "MCAXDTI2MTAwNTIzMTMwOFoYDzIxMjYwOTExMjMxMzA4WjAWMRQwEgYDVQQDDAtQaGlsaXBz" +
                "IEh1ZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABBhyDw02ajbQQ84+QPahKboQxUhuBMAC" +
                "48ncOZ+7TS/73I0B9A03gyhu87hlxEEkmyvWkR6iHf04dkJIlllbfTIwCgYIKoZIzj0EAwID" +
                "SQAwRgIhANIeKJ3xyFhQPHF2sJLElUuukEVmEx+6qilDFR2ec8TKAiEA53ERm4EwTEOV8hic" +
                "Y3QZJmBZTbaFqb/MHaNtb3f3rxo="
    }
}
