package fi.goodconsulting.kaukosaadin.device.hue

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HueProtocolTest {
    @Test fun pairingBodyRequestsAnAppKeyForThisApp() {
        val body = JSONObject(HueProtocol.pairingBody())
        assertEquals(HueProtocol.PAIRING_DEVICE_TYPE, body.getString("devicetype"))
        assertTrue(body.getBoolean("generateclientkey"))
    }

    @Test fun aSuccessReplyYieldsTheAppKey() {
        val result =
            HueProtocol.pairingResult(
                """[{"success":{"username":"a4e08834-0893-4013-b646-738582ec15c9","clientkey":"client-secret"}}]""",
            )
        assertEquals(
            HuePairingResult.Paired("a4e08834-0893-4013-b646-738582ec15c9", "client-secret"),
            result,
        )
    }

    @Test fun aSuccessReplyWithoutAClientKeyKeepsTheAppKey() {
        val result = HueProtocol.pairingResult("""[{"success":{"username":"abc-123"}}]""")
        assertEquals(HuePairingResult.Paired("abc-123", null), result)
    }

    @Test fun type101IsTheLinkButtonNotPressedState() {
        val result =
            HueProtocol.pairingResult(
                """[{"error":{"type":101,"address":"","description":"link button not pressed"}}]""",
            )
        assertEquals(HuePairingResult.LinkButtonNotPressed, result)
    }

    @Test fun otherErrorTypesAreRejectedWithTheirNumberNotTheirText() {
        val result = HueProtocol.pairingResult("""[{"error":{"type":7,"description":"invalid value"}}]""")
        assertEquals(HuePairingResult.Rejected("The bridge rejected the pairing request (error 7)."), result)
    }

    @Test fun malformedAndEmptyRepliesAreRejected() {
        val generic = HuePairingResult.Rejected("Unexpected pairing response from the bridge.")
        assertEquals(generic, HueProtocol.pairingResult(""))
        assertEquals(generic, HueProtocol.pairingResult("not json"))
        assertEquals(generic, HueProtocol.pairingResult("{}"))
        assertEquals(generic, HueProtocol.pairingResult("[]"))
        assertEquals(generic, HueProtocol.pairingResult("""[{"success":{}}]"""))
        assertEquals(generic, HueProtocol.pairingResult("""[{"error":{}}]"""))
    }

    @Test fun anAppKeyIsOpaqueTextWithNoAssumedFormat() {
        // The bridge's key format is unverified; any non-blank username is kept verbatim.
        assertEquals(HuePairingResult.Paired("not-a-uuid", null), HueProtocol.pairingResult("""[{"success":{"username":"not-a-uuid"}}]"""))
        assertEquals(
            HuePairingResult.Rejected("Unexpected pairing response from the bridge."),
            HueProtocol.pairingResult("""[{"success":{"username":""}}]"""),
        )
    }

    @Test fun aManualAddressMustBeALanIpv4() {
        assertEquals("192.168.1.42", HueProtocol.ipv4("192.168.1.42"))
        val bad =
            listOf(
                "http://192.168.1.42",
                "192.168.1",
                "192.168.1.256",
                "192.168.1.042",
                "127.0.0.1",
                "0.1.2.3",
                "224.0.0.1",
                "169.254.169.254",
            )
        for (value in bad) assertThrows(IllegalArgumentException::class.java) { HueProtocol.ipv4(value) }
    }
}
