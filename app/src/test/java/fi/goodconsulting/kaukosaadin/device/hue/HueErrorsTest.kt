package fi.goodconsulting.kaukosaadin.device.hue

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * The bridge's failures become the text the screens show. The v2 error `type` wins over the HTTP
 * status; without an envelope the status decides. The peer-controlled `description` is never shown.
 * These are pure mappings, so they are checked here rather than on hardware.
 */
class HueErrorsTest {
    @Test fun anErrorEnvelopeIsReportedByItsTypeBeforeTheStatus() {
        val body = """{"errors":[{"description":"rejected","type":4}],"data":[]}"""
        assertEquals("The bridge refused the request (Hue error 4).", HueErrors.message(401, body))
    }

    @Test fun aStatusWithoutAnEnvelopeGetsOurOwnText() {
        assertEquals("The bridge rejected the app key. Pair the bridge again.", HueErrors.message(401, ""))
        assertEquals("The bridge refused this request.", HueErrors.message(403, ""))
        assertEquals("The bridge no longer has that light, room or group.", HueErrors.message(404, ""))
        assertEquals("The bridge is rate-limiting requests. Try again in a moment.", HueErrors.message(429, ""))
        assertEquals("The bridge reported an internal error (500).", HueErrors.message(500, ""))
        assertEquals("The bridge reported an internal error (503).", HueErrors.message(503, ""))
        assertEquals("The bridge refused the request (418).", HueErrors.message(418, ""))
    }

    @Test fun aRefusedEventStreamNamesTheStatus() {
        assertEquals("The bridge refused the event stream (401).", HueErrors.streamRefused(401))
    }

    @Test fun transportFaultsMapToTheirVisibleText() {
        val refused = HueStreamException("The bridge refused the event stream (401).")
        assertEquals("The bridge refused the event stream (401).", HueErrors.transportFailure(refused))
        assertEquals(HueErrors.TRUST_CHANGED, HueErrors.transportFailure(SSLException("bad cert")))
        assertEquals(HueErrors.TRUST_CHANGED, HueErrors.transportFailure(CertificateException("bad cert")))
        assertEquals(HueErrors.UNREACHABLE, HueErrors.transportFailure(IOException("no route")))
    }
}
