package fi.goodconsulting.kaukosaadin.device.companion

import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.json.JSONObject
import java.math.BigInteger

/** Identical synthetic checks on the host JVM and the phone. Never prints key material. */
internal object CompanionCryptoCheck {
    const val REVISION = "b277a4c8222ecdcbaab8a24e3e713ca44765adb4"

    private fun String.bytes(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun JSONObject.bytes(key: String) = getString(key).bytes()

    private fun same(
        actual: ByteArray,
        expected: ByteArray,
    ) {
        check(actual.contentEquals(expected))
    }

    private fun changed(bytes: ByteArray) = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

    private inline fun <reified T : Exception> rejects(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (e: Exception) {
            check(e is T)
            rejected = true
        }
        check(rejected) { "Invalid input was accepted" }
    }

    fun run(json: String): List<String> {
        val data = JSONObject(json)
        check(data.getString("revision") == REVISION)
        val passed = mutableListOf<String>()

        fun step(
            name: String,
            block: () -> Unit,
        ) {
            try {
                block()
            } catch (e: Exception) {
                // No exception cause/message: could contain future peer or credential data.
                error("$name failed (${e.javaClass.simpleName})")
            }
            passed += "$name PASS"
        }
        step("SRP-3072/SHA-512 + server proof") {
            val group = SRP6StandardGroups.rfc5054_3072
            check(group.n == BigInteger(data.getString("srpPrime").replace("\n", "").trim(), 16))
            check(group.g == BigInteger(data.getString("srpGenerator"), 16))
            val vectors = data.getJSONArray("srp")
            for (i in 0 until vectors.length()) {
                val v = vectors.getJSONObject(i)

                fun setup(
                    pin: String = v.getString("pin"),
                    peer: ByteArray = v.bytes("serverPublic"),
                ) = CompanionCrypto.PairSetup(pin, v.bytes("salt"), peer, v.bytes("seed"))
                val setup = setup()
                same(setup.publicKey, v.bytes("public"))
                same(setup.clientProof, v.bytes("proof"))
                same(setup.verifyServer(v.bytes("serverProof")), v.bytes("key"))
                rejects<IllegalStateException> { setup.verifyServer(v.bytes("serverProof")) }
                val badProof = setup()
                rejects<SecurityException> { badProof.verifyServer(changed(v.bytes("serverProof"))) }
                rejects<IllegalStateException> { badProof.verifyServer(v.bytes("serverProof")) }
                rejects<SecurityException> { setup().verifyServer(byteArrayOf()) }
                rejects<SecurityException> { setup("9999").verifyServer(v.bytes("serverProof")) }
                rejects<IllegalArgumentException> { setup(peer = byteArrayOf(0)) }
                rejects<IllegalArgumentException> {
                    setup(
                        peer =
                            group.n
                                .toByteArray()
                                .drop(1)
                                .toByteArray(),
                    )
                }
                rejects<IllegalArgumentException> { setup(pin = "12345") }
            }
        }
        step("Ed25519 raw keys + signature rejection") {
            val v = data.getJSONObject("ed25519")
            same(CompanionCrypto.signingPublic(v.bytes("seed")), v.bytes("public"))
            same(CompanionCrypto.sign(v.bytes("seed"), v.bytes("message")), v.bytes("signature"))
            CompanionCrypto.verify(v.bytes("public"), v.bytes("message"), v.bytes("signature"))
            rejects<SecurityException> { CompanionCrypto.verify(v.bytes("public"), v.bytes("message"), changed(v.bytes("signature"))) }
            rejects<SecurityException> { CompanionCrypto.verify(v.bytes("public"), changed(v.bytes("message")), v.bytes("signature")) }
            rejects<SecurityException> { CompanionCrypto.verify(v.bytes("public"), v.bytes("message"), ByteArray(63)) }
        }
        step("X25519 raw agreement + low-order rejection") {
            val v = data.getJSONObject("x25519")
            same(CompanionCrypto.exchangePublic(v.bytes("seed")), v.bytes("public"))
            same(CompanionCrypto.sharedSecret(v.bytes("seed"), v.bytes("peer")), v.bytes("shared"))
            rejects<SecurityException> { CompanionCrypto.sharedSecret(v.bytes("seed"), ByteArray(32)) }
            rejects<IllegalArgumentException> { CompanionCrypto.sharedSecret(v.bytes("seed"), ByteArray(31)) }
        }
        step("HKDF-SHA-512 setup/verify/direction labels") {
            val vectors = data.getJSONArray("hkdf")
            for (i in 0 until vectors.length()) {
                val v = vectors.getJSONObject(i)
                same(CompanionCrypto.hkdf(v.getString("salt"), v.getString("info"), v.bytes("secret")), v.bytes("key"))
            }
            check(!vectors.getJSONObject(4).bytes("key").contentEquals(vectors.getJSONObject(5).bytes("key")))
        }
        step("ChaCha20-Poly1305 pairing nonces + tamper rejection") {
            val vectors = data.getJSONArray("pairing")
            val message = data.getJSONObject("ed25519").bytes("message")
            for (i in 0 until vectors.length()) {
                val v = vectors.getJSONObject(i)
                same(CompanionCrypto.aead(true, v.bytes("key"), v.bytes("nonce"), message), v.bytes("ciphertext"))
                same(CompanionCrypto.aead(false, v.bytes("key"), v.bytes("nonce"), v.bytes("ciphertext")), message)
                rejects<SecurityException> { CompanionCrypto.aead(false, v.bytes("key"), v.bytes("nonce"), changed(v.bytes("ciphertext"))) }
                rejects<SecurityException> { CompanionCrypto.aead(false, v.bytes("key"), changed(v.bytes("nonce")), v.bytes("ciphertext")) }
            }
        }
        step("Transport 12-byte LE nonces / AAD / independent counters") {
            val v = data.getJSONObject("transport")
            val frames = v.getJSONArray("frames")
            val outKey = v.bytes("outKey")
            val inKey = v.bytes("inKey")
            val payload = v.bytes("payload")
            val header = v.bytes("header")
            for (i in 0 until frames.length()) {
                val frame = frames.getJSONObject(i)
                same(CompanionCrypto.aead(true, outKey, frame.bytes("nonce"), payload, header), frame.bytes("ciphertext"))
                same(CompanionCrypto.aead(false, inKey, frame.bytes("nonce"), frame.bytes("incoming"), header), payload)
                rejects<SecurityException> {
                    CompanionCrypto.aead(
                        false,
                        inKey,
                        frame.bytes("nonce"),
                        frame.bytes("incoming"),
                        changed(header),
                    )
                }
                rejects<SecurityException> { CompanionCrypto.aead(false, outKey, frame.bytes("nonce"), frame.bytes("incoming"), header) }
                rejects<SecurityException> {
                    CompanionCrypto.aead(
                        false,
                        inKey,
                        frame.bytes("nonce"),
                        changed(frame.bytes("incoming")),
                        header,
                    )
                }
                rejects<SecurityException> { CompanionCrypto.aead(false, inKey, frame.bytes("nonce"), ByteArray(15), header) }
            }
            CompanionCrypto.Session(outKey, inKey).use { session ->
                for (counter in 0..256) {
                    val encrypted = session.encrypt(payload, header)
                    val index =
                        when (counter) {
                            0 -> 0
                            1 -> 1
                            256 -> 2
                            else -> -1
                        }
                    if (index >= 0) same(encrypted, frames.getJSONObject(index).bytes("ciphertext"))
                }
                // Receiving starts at zero even after 257 sends.
                same(session.decrypt(frames.getJSONObject(0).bytes("incoming"), header), payload)
                same(session.decrypt(frames.getJSONObject(1).bytes("incoming"), header), payload)
                rejects<SecurityException> { session.decrypt(changed(frames.getJSONObject(1).bytes("incoming")), header) }
                rejects<IllegalStateException> { session.encrypt(payload, header) }
                rejects<IllegalStateException> { session.decrypt(frames.getJSONObject(0).bytes("incoming"), header) }
            }
            CompanionCrypto.Session(outKey, inKey).use { session ->
                rejects<SecurityException> { session.decrypt(changed(frames.getJSONObject(0).bytes("incoming")), header) }
                rejects<IllegalStateException> { session.decrypt(frames.getJSONObject(0).bytes("incoming"), header) }
            }
            val closed = CompanionCrypto.Session(outKey, inKey).apply { close() }
            rejects<IllegalStateException> { closed.encrypt(payload, header) }
        }
        return passed
    }
}
