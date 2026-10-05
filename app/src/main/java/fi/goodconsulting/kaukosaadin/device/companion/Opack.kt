package fi.goodconsulting.kaukosaadin.device.companion

import java.io.ByteArrayOutputStream

/** OPACK subset adapted from pinned pyatv support/opack.py: null, booleans, non-negative
 * integers, doubles, strings, bytes, lists and string-keyed maps. Encoding is byte-identical
 * to pyatv, including back-references. Decoding is bounded; peer input is untrusted.
 */
internal object Opack {
    private const val MAX_DEPTH = 16
    private const val MAX_ITEMS = 4096

    fun pack(value: Any?): ByteArray = Packer().pack(value)

    fun unpack(data: ByteArray): Any? {
        val reader = Reader(data)
        val value = reader.value(0)
        if (reader.position != data.size) throw ProtocolException("Trailing OPACK data.")
        return value
    }

    private class Packer {
        private val objects = mutableListOf<ByteArray>()

        fun pack(value: Any?): ByteArray {
            val packed = when (value) {
                null -> byteArrayOf(0x04)
                is Boolean -> byteArrayOf(if (value) 0x01 else 0x02)
                is Int -> integer(value.toLong())
                is Long -> integer(value)
                is ULong -> if (value <= Long.MAX_VALUE.toULong()) integer(value.toLong()) else byteArrayOf(0x33) + little(value.toLong(), 8)
                is Double -> byteArrayOf(0x36) + little(java.lang.Double.doubleToRawLongBits(value), 8)
                is String -> value.toByteArray(Charsets.UTF_8).let { sized(it, 0x40, 0x20, 0x61, 0x64) }
                is ByteArray -> sized(value, 0x70, 0x20, 0x91, 0x93)
                is List<*> -> collection(0xD0, value.size, value.map(::pack))
                is Map<*, *> -> collection(0xE0, value.size, value.entries.map { (k, v) ->
                    require(k is String) { "OPACK map keys must be strings." }
                    pack(k) + pack(v)
                })
                else -> throw IllegalArgumentException("Unsupported OPACK type ${value.javaClass.simpleName}")
            }
            val index = objects.indexOfFirst { it.contentEquals(packed) }
            return when {
                index in 0..0x20 -> byteArrayOf((0xA0 + index).toByte())
                index in 0x21..0xFF ->byteArrayOf(0xC1.toByte()) + little(index.toLong(), 1)
                index > 0xFF -> byteArrayOf(0xC2.toByte()) + little(index.toLong(), 2).also { require(index <= 0xFFFF) }
                else -> packed.also { if (it.size > 1) objects += it }
            }
        }

        private fun integer(value: Long): ByteArray {
            require(value >= 0) { "Negative OPACK integers are not supported." }
            return when {
                value < 0x28 -> byteArrayOf((value + 8).toByte())
                value <= 0xFF -> byteArrayOf(0x30) + little(value, 1)
                value <= 0xFFFF -> byteArrayOf(0x31) + little(value, 2)
                value <= 0xFFFFFFFFL -> byteArrayOf(0x32) + little(value, 4)
                else -> byteArrayOf(0x33) + little(value, 8)
            }
        }

        // Inline length for short values; otherwise a 1/2/(3)/4-byte little-endian length prefix.
        private fun sized(data: ByteArray, inlineBase: Int, inlineMax: Int, firstTag: Int, lastTag: Int): ByteArray {
            if (data.size <= inlineMax) return byteArrayOf((inlineBase + data.size).toByte()) + data
            val widths = if (inlineBase == 0x40) listOf(1, 2, 3, 4) else listOf(1, 2, 4)
            val width = widths.first { data.size.toLong() < (1L shl (8 * it)) }
            val tag = firstTag + widths.indexOf(width)
            check(tag <= lastTag)
            return byteArrayOf(tag.toByte()) + little(data.size.toLong(), width) + data
        }

        private fun collection(base: Int, count: Int, items: List<ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(base + minOf(count, 0xF))
            items.forEach(out::write)
            if (count >= 0xF) out.write(0x03)
            return out.toByteArray()
        }
    }

    private class Reader(private val data: ByteArray) {
        var position = 0
        private val objects = mutableListOf<Any?>()
        private var items = 0

        private fun byte(): Int {
            if (position >= data.size) throw ProtocolException("Truncated OPACK data.")
            return data[position++].toInt() and 0xFF
        }
        private fun bytes(count: Long): ByteArray {
            if (count < 0 || count > data.size - position) throw ProtocolException("Truncated OPACK data.")
            return data.copyOfRange(position, position + count.toInt()).also { position += count.toInt() }
        }
        private fun little(width: Int): Long {
            val raw = bytes(width.toLong())
            var value = 0L
            for (i in raw.indices.reversed()) value = (value shl 8) or (raw[i].toLong() and 0xFF)
            return value
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH || ++items > MAX_ITEMS) throw ProtocolException("OPACK data too complex.")
            val tag = byte()
            var remember = true
            val value: Any? = when {
                tag == 0x01 -> true.also { remember = false }
                tag == 0x02 -> false.also { remember = false }
                tag == 0x04 -> null.also { remember = false }
                tag == 0x05 -> bytes(16) // UUID
                tag == 0x06 -> little(8) // Absolute time, kept as raw integer like pyatv.
                tag in 0x08..0x2F -> (tag - 8).toLong().also { remember = false }
                tag == 0x35 -> java.lang.Float.intBitsToFloat(little(4).toInt()).toDouble()
                tag == 0x36 -> java.lang.Double.longBitsToDouble(little(8))
                tag in 0x30..0x33 -> little(1 shl (tag and 0xF)).also {
                    if (tag == 0x33 && it < 0) throw ProtocolException("OPACK integer out of range.")
                }
                tag in 0x40..0x60 -> string(bytes((tag - 0x40).toLong()))
                tag in 0x61..0x64 -> string(bytes(little(tag and 0xF)))
                tag in 0x70..0x90 -> bytes((tag - 0x70).toLong())
                tag in 0x91..0x94 -> bytes(little(1 shl ((tag and 0xF) - 1)))
                tag in 0xA0..0xC0 -> reference((tag - 0xA0).toLong())
                tag in 0xC1..0xC4 -> reference(little(tag - 0xC0))
                tag and 0xF0 == 0xD0 -> buildList { repeatItems(tag) { add(value(depth + 1)) } }.also { remember = false }
                tag and 0xF0 == 0xE0 -> buildMap<String, Any?> {
                    repeatItems(tag) {
                        val key = value(depth + 1) as? String ?: throw ProtocolException("Unsupported OPACK map key.")
                        put(key, value(depth + 1))
                    }
                }.also { remember = false }
                else -> throw ProtocolException("Unsupported OPACK tag.")
            }
            if (remember && objects.none { same(it, value) }) objects += value
            return value
        }

        private inline fun repeatItems(tag: Int, item: () -> Unit) {
            val count = tag and 0xF
            if (count != 0xF) repeat(count) { item() } else {
                while (true) {
                    if (position >= data.size) throw ProtocolException("Unterminated OPACK collection.")
                    if (data[position] == 0x03.toByte()) { position++; break }
                    item()
                }
            }
        }

        private fun reference(index: Long): Any? =
            if (index in objects.indices) objects[index.toInt()] else throw ProtocolException("Invalid OPACK reference.")

        private fun string(raw: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder()
            return try { decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString() } catch (_: java.nio.charset.CharacterCodingException) {
                throw ProtocolException("Invalid OPACK string.")
            }
        }

        private fun same(a: Any?, b: Any?) = if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b
    }

    private fun little(value: Long, width: Int) = ByteArray(width) { ((value ushr (8 * it)) and 0xFF).toByte() }
}

/** Malformed or unexpected peer data. Messages never include peer content. */
internal class ProtocolException(message: String) : java.io.IOException(message)
