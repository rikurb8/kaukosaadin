package fi.goodconsulting.kaukosaadin.device.companion

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** OPACK/TLV8 bytes must match pinned pyatv exactly (vectors from tools/generate_companion_vectors.py). */
class CompanionCodecTest {
    private val codec = JSONObject(checkNotNull(javaClass.classLoader!!.getResourceAsStream("companion-crypto-vectors.json"))
        .bufferedReader().use { it.readText() }).getJSONObject("codec")
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun opack(name: String) = hex(codec.getJSONObject("opack").getString(name))
    private val tail = ByteArray(256) { it.toByte() } + ByteArray(44)

    private val values: Map<String, Any?> = mapOf(
        "hid" to linkedMapOf("_i" to "_hidC", "_t" to 2, "_c" to linkedMapOf("_hBtS" to 1, "_hidC" to 5), "_x" to 12345),
        "systemInfo" to linkedMapOf("_i" to "_systemInfo", "_t" to 2, "_c" to linkedMapOf(
            "_bf" to 0, "_cf" to 512, "_clFl" to 128, "_i" to "cafecafecafe",
            "_idsID" to "4d797fd3-3538-427e-a47b-a32fc6cf3a6a".toByteArray(), "_pubID" to "AA:BB:CC:DD:EE:FF",
            "_sf" to 256, "_sv" to "170.18", "model" to "iPhone10,6", "name" to "Kaukosaadin"), "_x" to 65536),
        "references" to linkedMapOf("a" to "same-string", "b" to "same-string",
            "c" to listOf("same-string", ByteArray(40), ByteArray(40)), "d" to linkedMapOf("_hidC" to 7)),
        "endless" to (0 until 16).associate { "k%02d".format(it) to it },
        "scalars" to listOf(null, true, false, 0x27, 0x28, 0xFF, 0x100, 0x10000, 0x100000000L, 1.5, "x".repeat(40), tail),
        "sessionStop" to mapOf("_sid" to ((0xFFFFFFFFuL shl 32) or 0x12345678uL)),
    )

    @Test fun opackMatchesReferenceBytes() {
        for ((name, value) in values) assertArrayEquals(name, opack(name), Opack.pack(value))
    }

    @Test fun opackDecodesReferenceBytes() {
        for ((name, value) in values) {
            if (name == "sessionStop") continue // > Long.MAX_VALUE is rejected on decode by design.
            assertEquals(name, normalize(value), normalize(Opack.unpack(opack(name))))
        }
        assertThrows(ProtocolException::class.java) { Opack.unpack(opack("sessionStop")) }
    }

    @Test fun opackRejectsMalformedInput() {
        val hid = opack("hid")
        for (cut in 1 until hid.size) assertThrows(ProtocolException::class.java) { Opack.unpack(hid.copyOf(cut)) }
        assertThrows(ProtocolException::class.java) { Opack.unpack(hid + 0x08) }
        assertThrows(ProtocolException::class.java) { Opack.unpack(byteArrayOf(0xA0.toByte())) }
        assertThrows(ProtocolException::class.java) { Opack.unpack(byteArrayOf(0xEF.toByte())) }
        assertThrows(ProtocolException::class.java) { Opack.unpack(byteArrayOf(0x93.toByte(), -1, -1, -1, 0x7F)) }
        assertThrows(ProtocolException::class.java) { Opack.unpack(ByteArray(64) { 0xD1.toByte() } + 0x08) }
        assertThrows(ProtocolException::class.java) { Opack.unpack(byteArrayOf(0x42, 0xC3.toByte(), 0x28)) }
    }

    @Test fun tlv8MatchesReferenceAndReassemblesFragments() {
        val name = Opack.pack(mapOf("name" to "Kaukosaadin"))
        val expected = hex(codec.getString("tlv8"))
        assertArrayEquals(expected, Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to tail, Tlv8.NAME to name))
        val read = Tlv8.read(expected)
        assertArrayEquals(tail, read[Tlv8.ENCRYPTED_DATA])
        assertArrayEquals(name, read[Tlv8.NAME])
        assertThrows(ProtocolException::class.java) { Tlv8.read(expected.copyOf(expected.size - 1)) }
        assertThrows(ProtocolException::class.java) { Tlv8.read(byteArrayOf(6)) }
    }

    // Decoded integers are Long and bytes are arrays; compare structurally.
    private fun normalize(value: Any?): Any? = when (value) {
        is Int -> value.toLong()
        is ByteArray -> value.toList()
        is List<*> -> value.map(::normalize)
        is Map<*, *> -> value.entries.associate { it.key to normalize(it.value) }
        else -> value
    }
}
