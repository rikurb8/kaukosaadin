package fi.goodconsulting.kaukosaadin.device.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class CompanionSessionTest {
    @Test fun anOpenLinkIsReusedWithoutOpeningAnother() {
        link().use { current ->
            assertSame(current, companionSession(current) { error("An open session must be reused.") })
        }
    }

    @Test fun reconnectAfterTheLinkClosesOpensAFreshLink() {
        val closed = companionSession(null, ::link)
        closed.close()
        companionSession(closed, ::link).use { connected ->
            assertNotSame(closed, connected)
            assertFalse(connected.closed)
        }
    }

    @Test fun aFailedReconnectIsNotReportedAsTheOldSession() {
        val closed = link().apply { close() }
        assertThrows(IOException::class.java) {
            companionSession(closed) { throw IOException("Unreachable") }
        }
    }

    private fun link() = CompanionLink(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
}
