package com.lgtvremote.discovery

import org.junit.Assert.*
import org.junit.Test

class TVDiscoveryTest {
    @Test fun parseLgRepliesAndRejectUnrelatedTraffic() {
        val response = "HTTP/1.1 200 OK\r\nST: urn:lge-com:service:webos-second-screen:1\r\n" +
            "DLNADeviceName.lge.com: Living%20Room%20LG\r\nLOCATION: http://192.168.1.2:1787/\r\n\r\n"
        val tv = TVDiscovery.parseResponse(response, "192.168.1.2")!!
        assertEquals("192.168.1.2", tv.ip)
        assertEquals("Living Room LG", tv.name)
        assertEquals("http://192.168.1.2:1787/", tv.location)
        assertNotNull(TVDiscovery.parseResponse(response.lowercase(), "192.168.1.2"))
        assertEquals("192.168.1.2", TVDiscovery.parseResponse(
            "HTTP/1.1 200 OK\r\nST: urn:lge-com:service:webos-second-screen:1\r\n", "192.168.1.2")!!.name)
        assertEquals("Bad%XX", TVDiscovery.parseResponse(response.replace("Living%20Room%20LG", "Bad%XX"), "192.168.1.2")!!.name)
        assertEquals("LGTV", TVDiscovery.parseResponse(response.replace("Living%20Room%20LG", "LG%0ATV"), "192.168.1.2")!!.name)
        assertNull(TVDiscovery.parseResponse(response.replace("200 OK", "404 Not Found"), "192.168.1.2"))
        assertNull(TVDiscovery.parseResponse(response.replace("webos-second-screen:1", "other-service:1"), "192.168.1.2"))
        assertNull(TVDiscovery.parseResponse("unrelated lge traffic", "192.168.1.2"))
        assertNull(TVDiscovery.parseResponse(response + "x".repeat(4096), "192.168.1.2"))
    }

    @Test fun scannerHonorsDeadlineWithoutTv() {
        val start = System.nanoTime()
        // Real UDP scan; an unavailable route must fail promptly, not hang or masquerade as success.
        val failure = runCatching { TVDiscovery().scanNetwork(250) }.exceptionOrNull()
        if (failure != null) assertTrue("Unexpected discovery failure: $failure", failure is java.io.IOException)
        assertTrue("Discovery exceeded its bounded timeout", (System.nanoTime() - start) / 1_000_000 < 2500)
    }
}
