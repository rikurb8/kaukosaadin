package fi.goodconsulting.kaukosaadin.device.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class HueDiscoveryTest {
    private val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 7))

    @Test fun aResolvedBridgeKeepsItsAdvertisedNameAndAddress() {
        val bridge = HueDiscovery.endpoint("Philips Hue - ecb5fafffe0a55da", address, 80)!!
        assertEquals("Philips Hue - ecb5fafffe0a55da", bridge.name)
        assertEquals(address, bridge.address)
        assertNull(bridge.model)
    }

    @Test fun namesAreSanitisedAndBounded() {
        assertEquals("Philips Hue", HueDiscovery.endpoint("Philips Hue\n", address, 80)!!.name)
        assertEquals(160, HueDiscovery.endpoint("x".repeat(200), address, 80)!!.name.length)
        assertEquals("Hue Bridge", HueDiscovery.endpoint(" \t ", address, 80)!!.name)
    }

    @Test fun unusableEndpointsAreDropped() {
        for (port in listOf(0, -1, 65_536)) assertNull(HueDiscovery.endpoint("Bridge", address, port))
        assertNull(HueDiscovery.endpoint("Bridge", null, 80))
        for (bytes in listOf(byteArrayOf(0, 0, 0, 0), byteArrayOf(127, 0, 0, 1), byteArrayOf(224.toByte(), 0, 0, 1))) {
            assertNull(HueDiscovery.endpoint("Bridge", InetAddress.getByAddress(bytes), 80))
        }
        val ipv6 = Inet6Address.getByAddress(null, byteArrayOf(0xfe.toByte(), 0x80.toByte()) + ByteArray(13) + byteArrayOf(1), 7)
        assertEquals(ipv6, HueDiscovery.endpoint("Bridge", ipv6, 443)!!.address)
    }

    @Test fun optionalTxtModelIsReadWhenPresent() {
        val bridge = HueDiscovery.endpoint("Philips Hue", address, 80, mapOf("modelid" to "BSB002".toByteArray()))!!
        assertEquals("BSB002", bridge.model)
    }

    @Test fun txtModelIsLookedUpCaseInsensitivelyAndTrimmed() {
        val bridge = HueDiscovery.endpoint("Philips Hue", address, 80, mapOf("ModelId" to " BSB002 ".toByteArray()))!!
        assertEquals("BSB002", bridge.model)
    }

    @Test fun aMissingOrBlankTxtModelLeavesTheBridgeUsable() {
        // The exact TXT key name is unverified; discovery must not depend on it.
        val bridge = HueDiscovery.endpoint("Philips Hue", address, 80, mapOf("modelid" to "  ".toByteArray()))!!
        assertNull(bridge.model)
    }
}
