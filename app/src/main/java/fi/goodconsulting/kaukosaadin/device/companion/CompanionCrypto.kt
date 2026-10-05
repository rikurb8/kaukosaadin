package fi.goodconsulting.kaukosaadin.device.companion

import org.bouncycastle.crypto.agreement.srp.SRP6Client
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** Wire formats adapted from pinned pyatv; see assets/licenses/pyatv.txt and docs/apple-tv-companion.md.
 * BC lightweight primitives avoid Android's older global BC provider. No provider registration.
 */
internal object CompanionCrypto {
    private val random = SecureRandom()

    fun seed(): ByteArray = ByteArray(32).also(random::nextBytes)

    private fun unsigned(value: BigInteger): ByteArray =
        value.toByteArray().let {
            if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }

    private fun hash(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").run {
            parts.forEach(::update)
            digest()
        }

    /** HAP uses SRP-6a's secret, but NOT BC's generic SRP evidence/key encoding. */
    class PairSetup(
        pin: String,
        salt: ByteArray,
        serverPublic: ByteArray,
        privateSeed: ByteArray = seed(),
    ) {
        val publicKey: ByteArray
        val clientProof: ByteArray
        private var pendingKey: ByteArray?
        private val expectedServerProof: ByteArray

        init {
            require(pin.matches(Regex("[0-9]{4}"))) { "Enter the four-digit TV PIN." }
            require(salt.size == 16) { "Invalid pairing salt." }
            require(privateSeed.size == 32 && privateSeed.any { it != 0.toByte() }) { "Invalid SRP seed." }
            require(serverPublic.size in 1..384) { "Invalid SRP public key." }
            val group = SRP6StandardGroups.rfc5054_3072
            val peer = BigInteger(1, serverPublic)
            require(peer > BigInteger.ZERO && peer < group.n) { "Invalid SRP public key." }
            val srp =
                object : SRP6Client() {
                    override fun selectPrivateValue(): BigInteger = BigInteger(1, privateSeed)
                }.apply { init(group, SHA512Digest(), random) }
            val identity = "Pair-Setup".toByteArray(Charsets.UTF_8)
            // The SRP password is the displayed four digits; pyatv's pairing handler zero-fills before SRP.
            publicKey = unsigned(srp.generateClientCredentials(salt, identity, pin.toByteArray(Charsets.UTF_8)))
            val key = hash(unsigned(srp.calculateSecret(peer)))
            val groupHash = unsigned(BigInteger(1, hash(unsigned(group.n))).xor(BigInteger(1, hash(unsigned(group.g)))))
            clientProof = hash(groupHash, unsigned(BigInteger(1, hash(identity))), salt, publicKey, unsigned(peer), key)
            expectedServerProof = hash(publicKey, clientProof, key)
            pendingKey = key
        }

        /** Check the ACTUAL received M4 proof before exposing K. A failed attempt cannot be retried. */
        fun verifyServer(proof: ByteArray): ByteArray {
            val key = checkNotNull(pendingKey) { "Pairing attempt already consumed. Request a new PIN." }
            pendingKey = null
            try {
                if (proof.size != 64 || !MessageDigest.isEqual(expectedServerProof, proof)) {
                    throw SecurityException("TV pairing proof rejected. Request a new PIN.")
                }
                return key.copyOf()
            } finally {
                key.fill(0)
            }
        }
    }

    fun hkdf(
        salt: String,
        info: String,
        secret: ByteArray,
    ): ByteArray =
        ByteArray(32).also {
            HKDFBytesGenerator(SHA512Digest()).apply {
                init(HKDFParameters(secret, salt.toByteArray(Charsets.UTF_8), info.toByteArray(Charsets.UTF_8)))
                generateBytes(it, 0, it.size)
            }
        }

    fun signingPublic(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "Invalid signing seed." }
        return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    fun sign(
        seed: ByteArray,
        message: ByteArray,
    ): ByteArray {
        require(seed.size == 32) { "Invalid signing seed." }
        return Ed25519Signer().run {
            init(true, Ed25519PrivateKeyParameters(seed, 0))
            update(message, 0, message.size)
            generateSignature()
        }
    }

    fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ) {
        if (publicKey.size != 32 || signature.size != 64) throw SecurityException("Invalid TV signature format.")
        val valid =
            Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(message, 0, message.size)
                verifySignature(signature)
            }
        if (!valid) throw SecurityException("TV signature rejected.")
    }

    fun exchangePublic(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "Invalid exchange seed." }
        return X25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    fun sharedSecret(
        seed: ByteArray,
        peer: ByteArray,
    ): ByteArray {
        require(seed.size == 32 && peer.size == 32) { "Invalid exchange key." }
        return ByteArray(32).also {
            try {
                X25519PrivateKeyParameters(seed, 0).generateSecret(X25519PublicKeyParameters(peer, 0), it, 0)
            } catch (_: IllegalStateException) {
                throw SecurityException("TV exchange key rejected.")
            }
        }
    }

    /** Pairing uses four zero bytes + an 8-byte ASCII nonce; transport uses a full 12-byte LE counter. */
    fun aead(
        encrypt: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        message: ByteArray,
        aad: ByteArray = byteArrayOf(),
    ): ByteArray {
        require(key.size == 32 && nonce.size in setOf(8, 12)) { "Invalid encryption key or nonce." }
        val paddedNonce = if (nonce.size == 8) ByteArray(4) + nonce else nonce
        val cipher = ChaCha20Poly1305().apply { init(encrypt, AEADParameters(KeyParameter(key), 128, paddedNonce, aad)) }
        val output = ByteArray(cipher.getOutputSize(message.size))
        try {
            val count = cipher.processBytes(message, 0, message.size, output, 0)
            val length = count + cipher.doFinal(output, count)
            return output.copyOf(length)
        } catch (_: org.bouncycastle.crypto.InvalidCipherTextException) {
            output.fill(0)
            throw SecurityException("Encrypted TV message rejected; reconnect with a fresh session.")
        }
    }

    /** Per-connection direction keys/counters; any failure poisons the session, never retries a nonce. */
    class Session(
        outKey: ByteArray,
        inKey: ByteArray,
    ) : AutoCloseable {
        private val outputKey = outKey.copyOf()
        private val inputKey = inKey.copyOf()
        private val outputNonce = ByteArray(12)
        private val inputNonce = ByteArray(12)
        private var closed = false

        init {
            require(outKey.size == 32 && inKey.size == 32) { "Invalid session keys." }
        }

        @Synchronized fun encrypt(
            message: ByteArray,
            header: ByteArray,
        ): ByteArray = crypt(true, message, header)

        @Synchronized fun decrypt(
            message: ByteArray,
            header: ByteArray,
        ): ByteArray = crypt(false, message, header)

        private fun crypt(
            encrypt: Boolean,
            message: ByteArray,
            header: ByteArray,
        ): ByteArray {
            check(!closed) { "Session closed. Reconnect before sending commands." }
            try {
                require(header.size == 4) { "Invalid Companion header." }
                val counter = if (encrypt) outputNonce else inputNonce
                val nonce = counter.copyOf()
                // Reserve the nonce even if encryption fails. Exhaustion closes the connection.
                check(counter.any { it != 0xff.toByte() }) { "Session nonce exhausted. Reconnect." }
                for (i in counter.indices) {
                    counter[i] = (counter[i] + 1).toByte()
                    if (counter[i] != 0.toByte()) break
                }
                return aead(encrypt, if (encrypt) outputKey else inputKey, nonce, message, header)
            } catch (e: Exception) {
                close()
                throw e
            }
        }

        @Synchronized override fun close() {
            closed = true
            outputKey.fill(0)
            inputKey.fill(0)
            outputNonce.fill(0)
            inputNonce.fill(0)
        }
    }
}
