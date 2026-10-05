package fi.goodconsulting.kaukosaadin.device.companion

import java.io.ByteArrayOutputStream

/** HAP TLV8 as in pinned pyatv auth/hap_tlv8.py: values over 255 bytes are split into
 * consecutive same-tag fragments and concatenated on read. Unknown tags are kept.
 */
internal object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val BACK_OFF = 0x08
    const val SIGNATURE = 0x0A
    const val NAME = 0x11

    fun write(vararg items: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in items) {
            require(tag in 0..0xFF && value.isNotEmpty()) { "Invalid TLV8 item." }
            var position = 0
            while (position < value.size) {
                val size = minOf(255, value.size - position)
                out.write(tag); out.write(size); out.write(value, position, size)
                position += size
            }
        }
        return out.toByteArray()
    }

    fun read(data: ByteArray): Map<Int, ByteArray> {
        val result = linkedMapOf<Int, ByteArray>()
        var position = 0
        while (position < data.size) {
            if (data.size - position < 2) throw ProtocolException("Truncated TLV8 data.")
            val tag = data[position].toInt() and 0xFF
            val length = data[position + 1].toInt() and 0xFF
            if (data.size - position - 2 < length) throw ProtocolException("Truncated TLV8 data.")
            val value = data.copyOfRange(position + 2, position + 2 + length)
            result[tag] = result[tag]?.plus(value) ?: value
            position += 2 + length
        }
        return result
    }
}
