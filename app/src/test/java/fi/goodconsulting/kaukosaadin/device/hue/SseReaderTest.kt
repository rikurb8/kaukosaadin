package fi.goodconsulting.kaukosaadin.device.hue

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseReaderTest {
    @Test fun readFrameParsesIdAndData() {
        val frame = reader("id: 1690000000:0\ndata: [{\"id\":\"x\"}]\n\n").readFrame()
        assertEquals(SseFrame("1690000000:0", """[{"id":"x"}]"""), frame)
    }

    @Test fun readFrameReturnsNullAtEndOfStream() {
        assertNull(reader("").readFrame())
    }

    @Test fun readFrameReturnsNullAfterTheLastFrame() {
        val reader = reader("id: 1:0\ndata: []\n\n")
        assertEquals(SseFrame("1:0", "[]"), reader.readFrame())
        assertNull(reader.readFrame())
    }

    @Test fun readFrameSkipsCommentsAndLeadingBlankLines() {
        val reader = reader(":heartbeat\n\nid: 1:0\ndata: []\n\n")
        assertEquals(SseFrame("1:0", "[]"), reader.readFrame())
    }

    @Test fun readFrameJoinsMultipleDataLines() {
        val frame = reader("data: one\ndata: two\n\n").readFrame()
        assertEquals(SseFrame(null, "one\ntwo"), frame)
    }

    @Test fun readFrameIgnoresUnknownFields() {
        val frame = reader("retry: 1000\nid: 5:0\ndata: x\n\n").readFrame()
        assertEquals(SseFrame("5:0", "x"), frame)
    }

    @Test fun readFrameHandlesCrLfLineEndings() {
        val frame = reader("id: 1:0\r\ndata: []\r\n\r\n").readFrame()
        assertEquals(SseFrame("1:0", "[]"), frame)
    }

    @Test fun readFrameDiscardsAPartialTrailingFrame() {
        assertNull(reader("id: 1:0\ndata: partial").readFrame())
    }

    private fun reader(text: String): SseReader = SseReader(Buffer().writeUtf8(text))
}
