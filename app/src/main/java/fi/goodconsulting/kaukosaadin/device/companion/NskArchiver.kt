// Binary plist tags, lengths and trailer layout are the bplist00 format, not tunables.
@file:Suppress("MagicNumber")

package fi.goodconsulting.kaukosaadin.device.companion

import java.io.ByteArrayOutputStream

private const val PLIST_HEADER = "bplist00"
private const val PLIST_TRAILER = 32
private const val MAX_DEPTH = 16
private const val MAX_OBJECTS = 4096

/** NSKeyedArchiver UID: the value indexes the archive's `$objects` array (pyatv's plistlib.UID). */
internal data class NskUid(
    val index: Int,
)

/** RTI keyboard session: the UUID the TV expects plus the keyboard's current text. */
internal data class NskTextSession(
    val uuid: ByteArray,
    val text: String,
)

/**
 * Minimal NSKeyedArchiver codec for the Companion RTI text payloads, adapted from pinned pyatv
 * (`keyed_archiver.py`, `plist_payloads/`). pyatv leans on Python's `plistlib`; Android has no plist
 * support, so this reads only enough to follow `$top` UID paths and writes pyatv's two fixed templates.
 */
internal object NskArchiver {
    /** Mirror of pyatv `read_archive_properties`: walk each path from `$top`, resolving UIDs. */
    fun readProperties(
        archive: ByteArray,
        vararg paths: List<String>,
    ): List<Any?> {
        val root = PlistReader(archive).root() as? Map<*, *> ?: throw ProtocolException("Not an NSKeyedArchiver archive.")
        val objects = root["\$objects"] as? List<*> ?: throw ProtocolException("Archive has no objects.")
        return paths.map { path ->
            var element = root["\$top"]
            for (key in path) {
                element = (element as? Map<*, *>)?.get(key) ?: return@map null
                if (element is NskUid) element = objects.getOrNull(element.index)
            }
            element
        }
    }

    /** Session UUID and current text, or null when the archive carries no keyboard session. */
    fun textSession(archive: ByteArray): NskTextSession? {
        val (uuid, text) =
            readProperties(
                archive,
                listOf("sessionUUID"),
                listOf("documentState", "docSt", "contextBeforeInput"),
            )
        val bytes = uuid as? ByteArray ?: return null
        return NskTextSession(bytes, text as? String ?: "")
    }

    fun clearText(uuid: ByteArray): ByteArray = PlistWriter().write(clearTemplate(uuid))

    fun insertText(
        uuid: ByteArray,
        text: String,
    ): ByteArray = PlistWriter().write(inputTemplate(uuid, text))

    // Templates copied from pinned pyatv plist_payloads/rti_text_operations.py; UID targets are its $objects indices.
    private fun clearTemplate(uuid: ByteArray) =
        linkedMapOf<String, Any?>(
            "\$version" to 100000,
            "\$archiver" to "RTIKeyedArchiver",
            "\$top" to linkedMapOf<String, Any?>("textOperations" to NskUid(1)),
            "\$objects" to
                listOf(
                    "\$null",
                    linkedMapOf<String, Any?>(
                        "\$class" to NskUid(7),
                        "targetSessionUUID" to NskUid(5),
                        "keyboardOutput" to NskUid(2),
                        "textToAssert" to NskUid(4),
                    ),
                    linkedMapOf<String, Any?>("\$class" to NskUid(3)),
                    classNode("TIKeyboardOutput"),
                    "",
                    linkedMapOf<String, Any?>("NS.uuidbytes" to uuid, "\$class" to NskUid(6)),
                    classNode("NSUUID"),
                    classNode("RTITextOperations"),
                ),
        )

    private fun inputTemplate(
        uuid: ByteArray,
        text: String,
    ) = linkedMapOf<String, Any?>(
        "\$version" to 100000,
        "\$archiver" to "RTIKeyedArchiver",
        "\$top" to linkedMapOf<String, Any?>("textOperations" to NskUid(1)),
        "\$objects" to
            listOf(
                "\$null",
                linkedMapOf<String, Any?>(
                    "keyboardOutput" to NskUid(2),
                    "\$class" to NskUid(7),
                    "targetSessionUUID" to NskUid(5),
                ),
                linkedMapOf<String, Any?>("insertionText" to NskUid(3), "\$class" to NskUid(4)),
                text,
                classNode("TIKeyboardOutput"),
                linkedMapOf<String, Any?>("NS.uuidbytes" to uuid, "\$class" to NskUid(6)),
                classNode("NSUUID"),
                classNode("RTITextOperations"),
            ),
    )

    private fun classNode(name: String) = linkedMapOf<String, Any?>("\$classname" to name, "\$classes" to listOf(name, "NSObject"))

    /** Bounded reader over the flat object table; UIDs stay unresolved for the caller to follow. */
    private class PlistReader(
        private val bytes: ByteArray,
    ) {
        private val values: Array<Any?>
        private val resolved: BooleanArray
        private val parsing: BooleanArray
        private val offsetSize: Int
        private val refSize: Int
        private val top: Int
        private val offsetsAt: Int

        init {
            if (bytes.size < PLIST_HEADER.length + PLIST_TRAILER) throw ProtocolException("Truncated archive.")
            if (String(bytes, 0, PLIST_HEADER.length, Charsets.US_ASCII) != PLIST_HEADER) {
                throw ProtocolException("Not a binary plist.")
            }
            val trailer = bytes.size - PLIST_TRAILER
            offsetSize = long(trailer + 6, 1).toInt()
            refSize = long(trailer + 7, 1).toInt()
            val count = long(trailer + 8, 8).toInt()
            if (count !in 1..MAX_OBJECTS || offsetSize !in 1..8 || refSize !in 1..8) {
                throw ProtocolException("Invalid archive header.")
            }
            values = arrayOfNulls(count)
            resolved = BooleanArray(count)
            parsing = BooleanArray(count)
            top = long(trailer + 16, 8).toInt()
            offsetsAt = long(trailer + 24, 8).toInt()
            if (top !in 0 until count || offsetsAt !in 0..bytes.size) throw ProtocolException("Invalid archive layout.")
        }

        fun root(): Any? = resolve(top, 0)

        private fun resolve(
            index: Int,
            depth: Int,
        ): Any? {
            if (index !in values.indices || depth > MAX_DEPTH) throw ProtocolException("Invalid archive reference.")
            if (resolved[index]) return values[index]
            if (parsing[index]) throw ProtocolException("Archive is cyclic.")
            parsing[index] = true
            val value = parse(index, depth)
            parsing[index] = false
            values[index] = value
            resolved[index] = true
            return value
        }

        // The flat tag table is the bplist00 format; a handler per type would hide the byte layout.
        @Suppress("CyclomaticComplexMethod", "ThrowsCount")
        private fun parse(
            index: Int,
            depth: Int,
        ): Any? {
            val at = long(offsetsAt + index * offsetSize, offsetSize).toInt()
            if (at !in 0 until bytes.size) throw ProtocolException("Invalid archive offset.")
            val marker = bytes[at].toInt() and 0xFF
            val info = marker and 0xF
            val body = at + 1
            return when (marker ushr 4) {
                0x0 ->
                    when (info) {
                        0x0 -> null
                        0x8 -> false
                        0x9 -> true
                        else -> throw ProtocolException("Unsupported archive value.")
                    }
                0x1 -> long(body, 1 shl info)
                0x2, 0x3 -> Double.fromBits(long(body, 8))
                0x4 -> data(body, info)
                0x5 -> ascii(body, info)
                0x6 -> utf16(body, info)
                0x8 -> NskUid(long(body, info + 1).toInt())
                0xA -> elements(body, info, depth)
                0xD -> dictionary(body, info, depth)
                else -> throw ProtocolException("Unsupported archive value.")
            }
        }

        private fun elements(
            body: Int,
            info: Int,
            depth: Int,
        ): List<Any?> {
            val (count, at) = length(body, info)
            return List(count) { resolve(long(at + it * refSize, refSize).toInt(), depth + 1) }
        }

        private fun dictionary(
            body: Int,
            info: Int,
            depth: Int,
        ): Map<String, Any?> {
            val (count, at) = length(body, info)
            val valuesAt = at + count * refSize
            val map = LinkedHashMap<String, Any?>(count)
            repeat(count) { i ->
                val key = resolve(long(at + i * refSize, refSize).toInt(), depth + 1) as? String
                map[key ?: throw ProtocolException("Archive key is not a string.")] =
                    resolve(long(valuesAt + i * refSize, refSize).toInt(), depth + 1)
            }
            return map
        }

        /** Container length is inline for short values, else an embedded integer object. */
        private fun length(
            body: Int,
            info: Int,
        ): Pair<Int, Int> {
            if (info != 0xF) return info to body
            val marker = bytes[body].toInt() and 0xFF
            if (marker ushr 4 != 0x1) throw ProtocolException("Invalid archive length.")
            val width = 1 shl (marker and 0xF)
            val count = long(body + 1, width).toInt()
            if (count < 0 || count > MAX_OBJECTS) throw ProtocolException("Archive value too large.")
            return count to (body + 1 + width)
        }

        private fun data(
            body: Int,
            info: Int,
        ): ByteArray {
            val (count, at) = length(body, info)
            return slice(at, count)
        }

        private fun ascii(
            body: Int,
            info: Int,
        ): String {
            val (count, at) = length(body, info)
            return String(slice(at, count), Charsets.US_ASCII)
        }

        private fun utf16(
            body: Int,
            info: Int,
        ): String {
            val (count, at) = length(body, info)
            return String(slice(at, count * 2), Charsets.UTF_16BE)
        }

        private fun slice(
            at: Int,
            count: Int,
        ): ByteArray {
            if (count < 0 || at < 0 || count > bytes.size - at) throw ProtocolException("Truncated archive value.")
            return bytes.copyOfRange(at, at + count)
        }

        private fun long(
            at: Int,
            width: Int,
        ): Long {
            if (width !in 1..8 || at < 0 || at + width > bytes.size) throw ProtocolException("Truncated archive.")
            var value = 0L
            repeat(width) { value = (value shl 8) or (bytes[at + it].toLong() and 0xFF) }
            return value
        }
    }

    /** Emits a valid bplist00 for the template tree; object ordering is its own, UIDs stay literal. */
    @Suppress("TooManyFunctions") // Encoder primitives for the bplist00 type table, one per wire shape.
    private class PlistWriter {
        private val nodes = mutableListOf<Node>()

        fun write(root: Any?): ByteArray {
            val top = ref(root, 0)
            val refSize = width(nodes.size)
            val out = ByteArrayOutputStream()
            out.write(PLIST_HEADER.toByteArray(Charsets.US_ASCII))
            val offsets = IntArray(nodes.size)
            nodes.forEachIndexed { index, node ->
                offsets[index] = out.size()
                out.write(encode(node, refSize))
            }
            val offsetTableAt = out.size()
            val offsetSize = width(offsetTableAt)
            offsets.forEach { out.write(bigEndian(it.toLong(), offsetSize)) }
            out.write(ByteArray(6))
            out.write(byteArrayOf(offsetSize.toByte(), refSize.toByte()))
            out.write(bigEndian(nodes.size.toLong(), 8))
            out.write(bigEndian(top.toLong(), 8))
            out.write(bigEndian(offsetTableAt.toLong(), 8))
            return out.toByteArray()
        }

        // Reserve the index before recursing so container children are appended after their parent.
        private fun ref(
            value: Any?,
            depth: Int,
        ): Int {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("Archive too deep.")
            val index = nodes.size
            nodes.add(Node.Raw(byteArrayOf(0x00)))
            nodes[index] = node(value, depth)
            return index
        }

        private fun node(
            value: Any?,
            depth: Int,
        ): Node =
            when (value) {
                null -> Node.Raw(byteArrayOf(0x00))
                is Boolean -> Node.Raw(byteArrayOf(if (value) 0x09.toByte() else 0x08.toByte()))
                is Int -> Node.Raw(integer(value.toLong()))
                is Long -> Node.Raw(integer(value))
                is Double -> Node.Raw(byteArrayOf(0x23) + bigEndian(java.lang.Double.doubleToRawLongBits(value), 8))
                is String -> Node.Raw(text(value))
                is ByteArray -> Node.Raw(sized(0x40, 0x4F, value))
                is NskUid -> Node.Raw(uid(value.index))
                is List<*> -> Node.Refs(value.map { ref(it, depth + 1) })
                is Map<*, *> ->
                    Node.Pairs(
                        value.keys.map { ref(it as? String ?: throw IllegalArgumentException("Archive keys are strings."), depth + 1) },
                        value.values.map { ref(it, depth + 1) },
                    )
                else -> throw IllegalArgumentException("Unsupported archive value.")
            }

        private fun encode(
            node: Node,
            refSize: Int,
        ): ByteArray =
            when (node) {
                is Node.Raw -> node.bytes
                is Node.Refs -> concat(listOf(count(0xA0, node.refs)) + node.refs.map { ref(it, refSize) })
                is Node.Pairs ->
                    concat(
                        listOf(count(0xD0, node.keys)) +
                            node.keys.map { ref(it, refSize) } +
                            node.values.map { ref(it, refSize) },
                    )
            }

        private fun ref(
            index: Int,
            refSize: Int,
        ): ByteArray = bigEndian(index.toLong(), refSize)

        private fun concat(parts: List<ByteArray>): ByteArray = parts.fold(ByteArray(0), ByteArray::plus)

        private fun count(
            base: Int,
            refs: List<Int>,
        ): ByteArray =
            if (refs.size < 0xF) {
                byteArrayOf((base + refs.size).toByte())
            } else {
                byteArrayOf((base + 0xF).toByte()) + integer(refs.size.toLong())
            }

        private fun integer(value: Long): ByteArray {
            val size =
                when {
                    value in -0x80..0x7F -> 1
                    value in -0x8000..0x7FFF -> 2
                    value in -0x80000000L..0x7FFFFFFFL -> 4
                    else -> 8
                }
            val tag = if (size == 8) 3 else Integer.numberOfTrailingZeros(size)
            return byteArrayOf((0x10 + tag).toByte()) + bigEndian(value, size)
        }

        private fun text(value: String): ByteArray =
            if (value.all { it.code < 0x80 }) {
                sized(0x50, 0x5F, value.toByteArray(Charsets.US_ASCII))
            } else {
                sized(0x60, 0x6F, value.toByteArray(Charsets.UTF_16BE), value.length)
            }

        private fun sized(
            inlineBase: Int,
            extendedTag: Int,
            raw: ByteArray,
            count: Int = raw.size,
        ): ByteArray =
            (
                if (count < 0xF) {
                    byteArrayOf((inlineBase + count).toByte())
                } else {
                    byteArrayOf(extendedTag.toByte()) + integer(count.toLong())
                }
            ) + raw

        private fun uid(index: Int): ByteArray {
            val size =
                when {
                    index < 0x100 -> 1
                    index < 0x10000 -> 2
                    index < 0x1000000 -> 3
                    else -> 4
                }
            return byteArrayOf((0x80 + size - 1).toByte()) + bigEndian(index.toLong(), size)
        }

        private fun width(value: Int): Int =
            when {
                value < 0x100 -> 1
                value < 0x10000 -> 2
                value < 0x100000000L -> 4
                else -> 8
            }

        private fun bigEndian(
            value: Long,
            size: Int,
        ): ByteArray = ByteArray(size) { ((value ushr (8 * (size - 1 - it))) and 0xFF).toByte() }

        private sealed interface Node {
            class Raw(
                val bytes: ByteArray,
            ) : Node

            class Refs(
                val refs: List<Int>,
            ) : Node

            class Pairs(
                val keys: List<Int>,
                val values: List<Int>,
            ) : Node
        }
    }
}
