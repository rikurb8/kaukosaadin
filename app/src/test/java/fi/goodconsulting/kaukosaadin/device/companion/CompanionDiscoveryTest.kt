package fi.goodconsulting.kaukosaadin.device.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class CompanionDiscoveryTest {
    @Test fun resolvedEndpointsKeepAdvertisedAddressAndPort() {
        val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 7))
        val device = CompanionDiscovery.endpoint("Example TV\n", address, 54321)!!
        assertEquals("Example TV", device.name)
        assertEquals(address, device.address)
        assertEquals(54321, device.port) // Never substitute a guessed/fixed Companion port.
        assertEquals(160, CompanionDiscovery.endpoint("x".repeat(200), address, 1)!!.name.length)
        assertEquals("Companion device", CompanionDiscovery.endpoint("\n", address, 65535)!!.name)
        for (port in listOf(-1, 0, 65536)) assertNull(CompanionDiscovery.endpoint("TV", address, port))
        assertNull(CompanionDiscovery.endpoint("TV", null, 1234))
        for (bytes in listOf(byteArrayOf(0, 0, 0, 0), byteArrayOf(127, 0, 0, 1), byteArrayOf(224.toByte(), 0, 0, 1))) {
            assertNull(CompanionDiscovery.endpoint("TV", InetAddress.getByAddress(bytes), 1234))
        }
        val ipv6 = Inet6Address.getByAddress(null, byteArrayOf(0xfe.toByte(), 0x80.toByte()) + ByteArray(13) + byteArrayOf(1), 7)
        assertEquals(ipv6, CompanionDiscovery.endpoint("IPv6 TV", ipv6, 54322)!!.address)
        assertEquals(7, (CompanionDiscovery.endpoint("IPv6 TV", ipv6, 54322)!!.address as Inet6Address).scopeId)
    }
}
