package fi.goodconsulting.kaukosaadin.device

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LgProtocolTest {
    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Expected rejection")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun pairingDoesNotRequireWakeDetails() {
        val pin = "AB".repeat(32)
        assertEquals(pin.lowercase(), LgProtocol.pairingFingerprint("192.168.1.2", pin))
        rejected { LgProtocol.pairingFingerprint("192.168.1.2", "") }
        rejected { LgProtocol.pairingFingerprint("192.168.1.2", "invalid") }
        rejected { LgProtocol.pairingFingerprint("not-an-address", pin) }
        // Wake remains independently validated; blank details must not produce a packet.
        rejected { LgProtocol.magicPacket("") }
        rejected { LgProtocol.ipv4("") }
    }

    @Test fun pinPairingExchangeAndRefusal() {
        val registration = JSONObject(LgProtocol.registration(null))
        assertEquals("PIN", registration.getJSONObject("payload").getString("pairingType"))
        val challenge = LgProtocol.response("{\"type\":\"response\",\"payload\":{\"pairingType\":\"pin\"}}")
        assertTrue(LgProtocol.needsPin(challenge, navigation = false))
        rejected { LgProtocol.needsPin(challenge, navigation = true) }
        rejected { LgProtocol.needsPin(JSONObject("{\"payload\":{\"pairingType\":\"PROMPT\"}}"), navigation = false) }
        val submission = JSONObject(LgProtocol.pinRequest("001234"))
        assertEquals("request", submission.getString("type"))
        assertEquals("ssap://pairing/setPin", submission.getString("uri"))
        assertEquals("001234", submission.getJSONObject("payload").getString("pin"))
        listOf("", "123", "123456789", "12ab56", " 123456", "１２３４５６").forEach { rejected { LgProtocol.pinRequest(it) } }
        val accepted = LgProtocol.response("{\"type\":\"response\",\"id\":\"pair-pin\",\"payload\":{\"returnValue\":true}}")
        assertFalse(LgProtocol.needsPin(accepted, navigation = false))
        assertNotEquals("registered", accepted.optString("type"))
        val registered = LgProtocol.response("{\"type\":\"registered\",\"payload\":{\"client-key\":\"fake-key\"}}")
        assertFalse(LgProtocol.needsPin(registered, navigation = false))
        assertEquals("fake-key", registered.getJSONObject("payload").getString("client-key"))
        rejected { LgProtocol.response("{\"type\":\"error\",\"id\":\"pair-pin\",\"error\":\"wrong PIN\"}") }
    }

    @Test fun mappingAndErrors() {
        val keys = listOf("UP", "DOWN", "LEFT", "RIGHT", "ENTER", "BACK")
        LgProtocol.Action.entries.drop(1).zip(keys).forEach { (action, key) ->
            assertEquals("type:button\nname:$key\n\n", LgProtocol.button(action))
        }
        rejected { LgProtocol.button(LgProtocol.Action.Wake) }
        val packet = LgProtocol.magicPacket("02:11:22:33:44:55")
        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xff.toByte() })
        repeat(16) { assertArrayEquals(byteArrayOf(2, 17, 34, 51, 68, 85), packet.copyOfRange(6 + it * 6, 12 + it * 6)) }
        listOf("", "ff:ff:ff:ff:ff:ff", "00:00:00:00:00:00", "02:11:22:33:44:GG").forEach { rejected { LgProtocol.magicPacket(it) } }
        assertEquals("192.168.1.2", LgProtocol.ipv4("192.168.1.2"))
        listOf("localhost", "127.0.0.1", "1.2.3.256", "01.2.3.4", "http://192.168.1.2", "224.1.1.1").forEach {
            rejected { LgProtocol.ipv4(it) }
        }
        val safe = "wss://192.168.1.2:3001/resources/input?token=example"
        assertEquals(safe, LgProtocol.pointerUrl(safe, "192.168.1.2"))
        listOf(
            "ws://192.168.1.2:3000/input",
            "wss://192.168.1.3:3001/input",
            "wss://192.168.1.2:443/input",
            "wss://user@192.168.1.2:3001/input",
        ).forEach { rejected { LgProtocol.pointerUrl(it, "192.168.1.2") } }
        rejected { LgProtocol.response("{\"type\":\"error\",\"error\":\"secret\"}") }
        rejected { LgProtocol.response("{\"type\":\"response\",\"payload\":{\"returnValue\":false}}") }
        assertEquals("registered", LgProtocol.response("{\"type\":\"registered\"}").getString("type"))
        val registration = JSONObject(LgProtocol.registration("test-key")).getJSONObject("payload")
        assertEquals("test-key", registration.getString("client-key"))
        assertEquals("PIN", registration.getString("pairingType"))
        assertFalse(JSONObject(LgProtocol.registration(null)).getJSONObject("payload").has("client-key"))
    }
}
