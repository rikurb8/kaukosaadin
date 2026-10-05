package com.lgtvremote.discovery

import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

// Adapted from audev482/lgtv-kotlin, commit 3bd6f623487b5e91fc4953666d41a32485101e4b.
// Source and local changes: docs/lg-g3.md#upstream-discovery-source
class TVDiscovery {
    data class DiscoveredTV(
        val ip: String,
        val name: String,
        val location: String? = null,
    )

    /** Upstream SSDP multicast/broadcast scan; no HTTP enrichment or pairing. */
    fun scanNetwork(timeoutMs: Int = DEFAULT_TIMEOUT_MS): List<DiscoveredTV> {
        require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            "Discovery timeout must be $MIN_TIMEOUT_MS–$MAX_TIMEOUT_MS ms."
        }
        val mSearch =
            buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 1\r\n")
                append("ST: $SEARCH_TARGET\r\n")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)
        val devices = mutableMapOf<String, DiscoveredTV>()
        val destinations =
            listOf(
                InetSocketAddress("239.255.255.250", 1900),
                InetSocketAddress("255.255.255.255", 1900),
            )
        // MulticastSocket's TTL API also works below Android 33, unlike DatagramSocket.setOption.
        MulticastSocket().use { socket ->
            socket.broadcast = true
            socket.timeToLive = 1
            val start = System.nanoTime()
            repeat(SEARCH_ROUNDS) { round ->
                var sent = false
                var sendError: java.io.IOException? = null
                for (destination in destinations) {
                    try {
                        socket.send(DatagramPacket(mSearch, mSearch.size, destination))
                        sent = true
                    } catch (e: java.io.IOException) {
                        sendError = e
                    }
                }
                if (!sent) throw sendError ?: java.io.IOException("Discovery send failed")
                val end = start + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong()) * (round + 1) / SEARCH_ROUNDS
                while (System.nanoTime() < end) {
                    val remaining = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())
                    socket.soTimeout = remaining.coerceIn(1, RECEIVE_TIMEOUT_MS).toInt()
                    try {
                        val packet = DatagramPacket(ByteArray(MAX_RESPONSE_BYTES), MAX_RESPONSE_BYTES)
                        socket.receive(packet)
                        absorb(packet, devices)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
            }
        }
        return devices.values.toList()
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 6000
        private const val MIN_TIMEOUT_MS = 250
        private const val MAX_TIMEOUT_MS = 15_000
        private const val RECEIVE_TIMEOUT_MS = 2000L
        private const val SEARCH_ROUNDS = 3
        private const val MAX_RESPONSE_BYTES = 4096
        private const val MAX_NAME_CHARS = 160
        private const val SEARCH_TARGET = "urn:lge-com:service:webos-second-screen:1"

        /** Records the first reply per address; replies we cannot attribute or have already seen are dropped. */
        private fun absorb(
            packet: DatagramPacket,
            devices: MutableMap<String, DiscoveredTV>,
        ) {
            val ip = packet.address.hostAddress ?: return
            if (ip in devices) return
            val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
            parseResponse(response, ip)?.let { devices[ip] = it }
        }

        /** Extracted upstream response parser, tightened at the untrusted LAN boundary. */
        internal fun parseResponse(
            response: String,
            ip: String,
        ): DiscoveredTV? {
            val lines = response.split("\r\n")
            val service =
                lines
                    .firstOrNull { it.startsWith("st:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
            if (response.length > MAX_RESPONSE_BYTES ||
                !lines.first().trim().equals("HTTP/1.1 200 OK", ignoreCase = true) ||
                !SEARCH_TARGET.equals(service, ignoreCase = true)
            ) {
                return null
            }
            var name = ip
            var location: String? = null
            for (line in lines) {
                when {
                    line.startsWith("dlnadevicename.lge.com:", ignoreCase = true) -> {
                        val rawName = line.substringAfter(':').trim()
                        name =
                            try {
                                URLDecoder.decode(rawName, "UTF-8")
                            } catch (_: IllegalArgumentException) {
                                rawName
                            }
                        name = name.filterNot { it.isISOControl() }.take(MAX_NAME_CHARS).ifBlank { ip }
                    }
                    line.startsWith("location:", ignoreCase = true) -> location = line.substringAfter(':').trim()
                }
            }
            return DiscoveredTV(ip, name, location)
        }
    }
}
