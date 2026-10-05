package fi.goodconsulting.kaukosaadin.device.companion

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RTI keyboard payloads must round-trip against pinned pyatv's NSKeyedArchiver shapes
 * (vectors from tools/generate_companion_vectors.py, which uses plistlib).
 */
class CompanionArchiverTest {
    private val rti =
        JSONObject(
            checkNotNull(javaClass.classLoader!!.getResourceAsStream("companion-crypto-vectors.json"))
                .bufferedReader()
                .use { it.readText() },
        ).getJSONObject("rti")

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val uuid = hex(rti.getString("uuid"))
    private val text = rti.getString("text")

    // pyatv plist_payloads read paths for the write templates.
    private fun readsClear(archive: ByteArray): Triple<ByteArray?, Any?, Any?> {
        val (id, asserted, inserted) =
            NskArchiver.readProperties(
                archive,
                listOf("textOperations", "targetSessionUUID", "NS.uuidbytes"),
                listOf("textOperations", "textToAssert"),
                listOf("textOperations", "keyboardOutput", "insertionText"),
            )
        return Triple(id as ByteArray?, asserted, inserted)
    }

    @Test fun readsPyatvFocusArchive() {
        val session = NskArchiver.textSession(hex(rti.getString("focus")))
        assertEquals(text, session?.text)
        assertArrayEquals(uuid, session?.uuid)
    }

    @Test fun pyatvPayloadsDecodeToSessionValues() {
        val (clearId, cleared, clearInsert) = readsClear(hex(rti.getString("clear")))
        assertArrayEquals(uuid, clearId)
        assertEquals("", cleared)
        assertNull(clearInsert)

        val (inputId, asserted, inserted) = readsClear(hex(rti.getString("input")))
        assertArrayEquals(uuid, inputId)
        assertNull(asserted)
        assertEquals(text, inserted)
    }

    @Test fun writtenPayloadsMatchPyatvShapes() {
        val clear = NskArchiver.clearText(uuid)
        assertTrue(String(clear, 0, 8, Charsets.US_ASCII) == "bplist00")
        val (clearId, cleared, clearInsert) = readsClear(clear)
        assertArrayEquals(uuid, clearId)
        assertEquals("", cleared)
        assertNull(clearInsert)

        val input = NskArchiver.insertText(uuid, text)
        val (inputId, asserted, inserted) = readsClear(input)
        assertArrayEquals(uuid, inputId)
        assertNull(asserted)
        assertEquals(text, inserted)
    }

    @Test fun truncatedAndForeignArchivesFailClosed() {
        val focus = hex(rti.getString("focus"))
        assertThrows(ProtocolException::class.java) { NskArchiver.textSession(ByteArray(64)) }
        assertThrows(ProtocolException::class.java) { NskArchiver.textSession(focus.copyOf(focus.size / 2)) }
        assertThrows(ProtocolException::class.java) { NskArchiver.textSession(focus.copyOf(focus.size - 60)) }
    }
}
