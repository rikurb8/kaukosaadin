package fi.goodconsulting.kaukosaadin.device.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class HueDiscoveryTest {
    private val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 7))

    @Test fun aResolvedBridgeKeepsItsAdvertisedAddressAndPort() {
        val bridge = HueDiscovery.endpoint("Philips Hue - ecb5fafffe0a55da", address, 80)!!
        assertEquals("Philips Hue - ecb5fafffe0a55da", bridge.name)
        assertEquals(address, bridge.address)
        assertEquals(80, bridge.port) // The bridge advertises its own port; never substitute 443.
        assertNull(bridge.model)
        assertNull(bridge.bridgeId)
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

    @Test fun optionalTxtIdentityIsReadWhenPresent() {
        val attributes =
            mapOf(
                "bridgeid" to "ecb5fafffe0a55da".toByteArray(),
                "modelid" to "BSB002".toByteArray(),
            )
        val bridge = HueDiscovery.endpoint("Philips Hue", address, 80, attributes)!!
        assertEquals("BSB002", bridge.model)
        assertEquals("ecb5fafffe0a55da", bridge.bridgeId)
    }

    @Test fun txtIdentityIsLookedUpCaseInsensitivelyAndTrimmed() {
        val attributes = mapOf("BridgeId" to " ECb5FA ".toByteArray(), "modelid" to " BSB002 ".toByteArray())
        val bridge = HueDiscovery.endpoint("Philips Hue", address, 80, attributes)!!
        assertEquals("BSB002", bridge.model)
        assertEquals("ECb5FA", bridge.bridgeId)
    }

    @Test fun aMissingOrBlankTxtIdentityLeavesTheBridgeUsable() {
        // The exact TXT key names are unverified; discovery must not depend on them.
        val bridge = HueDiscovery.endpoint("Philips Hue", address, 80, mapOf("modelid" to "  ".toByteArray()))!!
        assertNull(bridge.model)
        assertNull(bridge.bridgeId)
    }
}
