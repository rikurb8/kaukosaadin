package fi.goodconsulting.kaukosaadin.device.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CompanionSkipSupportTest {
    @Test fun eachDirectionUsesItsOwnPinnedPyatvFlag() {
        val forward = CompanionSkipSupport.fromFlags(0x0200L)
        assertTrue(forward.allows(10.0))
        assertFalse(forward.allows(-10.0))
        val backward = CompanionSkipSupport.fromFlags(0x0400L)
        assertFalse(backward.allows(30.0))
        assertTrue(backward.allows(-5.5))
        assertEquals(CompanionSkipSupport(true, true), CompanionSkipSupport.fromFlags(0x0700L))
    }

    @Test fun invalidIntervalsAreRejectedBeforeAnyWireCommand() {
        for (seconds in listOf(0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val output = ByteArrayOutputStream()
            CompanionLink(ByteArrayInputStream(byteArrayOf()), output).use { link ->
                assertThrows(IllegalArgumentException::class.java) { link.skip(seconds) }
                assertEquals(0, output.size())
                assertTrue(link.closed)
            }
        }
    }

    @Test fun missingMalformedAndUnrelatedFlagsDisableSkipping() {
        for (value in listOf(null, "1536", 1536.0, -1L, 0L, 0x0100L)) {
            assertEquals(CompanionSkipSupport(), CompanionSkipSupport.fromFlags(value))
        }
    }
}
