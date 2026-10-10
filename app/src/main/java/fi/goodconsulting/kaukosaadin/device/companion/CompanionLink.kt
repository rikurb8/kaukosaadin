// pyatv Companion/HID wire format: command codes, frame sizes, masks and protocol timeouts.
@file:Suppress("MagicNumber")

package fi.goodconsulting.kaukosaadin.device.companion

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Field diagnostics only: our own failure text, never peer data or secrets. */
private const val LOG_TAG = "Kaukosaadin"

/** Apple TV remote buttons; codes from pinned pyatv protocols/companion/api.py HidCommand. */
enum class HidCommand(
    val code: Int,
) {
    Up(1),
    Down(2),
    Left(3),
    Right(4),
    Menu(5),
    Select(6),
    Home(7),
    VolumeUp(8),
    VolumeDown(9),
    Sleep(12),
    PlayPause(14),
}

/** pyatv InputAction: double tap = two press/release pairs on one connection; hold = 1 s down. */
enum class PressAction { Tap, DoubleTap, Hold }

/** One app the TV reports as launchable, with the name it shows on its own Home screen. */
data class AppleTvApp(
    val bundleId: String,
    val name: String,
)

/** tvOS on-screen keyboard focus pushed by the RTI service (`_tiStarted`/`_tiStopped`). */
data class CompanionKeyboardState(
    val focused: Boolean,
    val text: String,
)

/** Long-term pairing material, pyatv-compatible ("ltpk:ltsk:atv_id:client_id" hex). Secret: ltsk. */
internal class CompanionCredentials(
    val ltpk: ByteArray,
    val ltsk: ByteArray,
    val atvId: ByteArray,
    val clientId: ByteArray,
) {
    init {
        require(ltpk.size == 32 && ltsk.size == 32 && atvId.isNotEmpty() && clientId.isNotEmpty()) { "Invalid saved pairing." }
    }

    fun encode() = listOf(ltpk, ltsk, atvId, clientId).joinToString(":") { it.toHex() }

    fun wipe() = ltsk.fill(0)

    companion object {
        // Wire order is pyatv's ltpk:ltsk:atv_id:client_id; the 4-way destructuring keeps that mapping readable.
        @Suppress("DestructuringDeclarationWithTooManyEntries")
        fun decode(value: String): CompanionCredentials {
            val parts = value.split(":")
            require(parts.size == 4 && parts.all { it.length % 2 == 0 && it.all(Char::isLetterOrDigit) }) { "Invalid saved pairing." }
            val (ltpk, ltsk, atvId, clientId) =
                parts.map { hex ->
                    ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
                }
            return CompanionCredentials(ltpk, ltsk, atvId, clientId)
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}

/** Identity this phone presents in _systemInfo; values follow pyatv's defaults. Not secret. */
internal data class CompanionClientInfo(
    val name: String,
    val rpId: String,
    val deviceId: String,
    val model: String = "iPhone10,6",
)

/** The TV refused a pairing/verification/command step. Messages are ours, never peer text. */
internal class CompanionRejected(
    message: String,
) : IOException(message)

/**
 * One blocking Companion TCP connection, used by one operation at a time; adapted from pinned pyatv
 * (see assets/licenses/pyatv.txt and docs/apple-tv-companion.md). Unlike the reference it verifies
 * the M4 server proof, the accessory setup signature and the pair-verify final status. Any failure
 * leaves the link unusable; callers reconnect instead of retrying or replaying commands.
 */
@Suppress("TooManyFunctions") // Handshake, session, HID press, RTI text and the reader share one socket.
internal class CompanionLink(
    input: InputStream,
    output: OutputStream,
    private val socket: Socket? = null,
) : Closeable {
    private val input = input.buffered()
    private val output = output.buffered()
    private val sendLock = Any()

    // Correlated responses and pushed events are handled by the reader thread once verified.
    private val pending = ConcurrentHashMap<Long, ArrayBlockingQueue<Reply>>()
    private var reader: Thread? = null
    private var keyboardSession: NskTextSession? = null
    private var textStarted = false

    @Volatile internal var skipSupport = CompanionSkipSupport()
        private set

    /** Invoked on the reader thread on capability updates and on close to clear stale support. */
    @Volatile internal var skipListener: ((CompanionSkipSupport) -> Unit)? = null

    @Volatile private var session: CompanionCrypto.Session? = null
    private var xid = SecureRandom().nextInt(0x10000).toLong()
    private var localSid = 0L
    private var remoteSid = -1L

    /** Invoked on the reader thread when the TV's on-screen keyboard takes or loses focus. */
    @Volatile
    internal var keyboardListener: ((CompanionKeyboardState) -> Unit)? = null

    @Volatile
    internal var closed = false
        private set

    class PendingPairing internal constructor(
        internal val salt: ByteArray,
        internal val serverPublic: ByteArray,
    )

    /** One correlated reply, or an explicit close so a dead reader wakes waiters immediately. */
    private sealed interface Reply {
        class Message(
            val message: Map<*, *>,
        ) : Reply

        data object Closed : Reply
    }

    /** PS M1/M2. The TV shows a four-digit PIN after this returns. */
    fun startPairing(): PendingPairing =
        guarded {
            val reply = exchangeAuth(PS_START, Tlv8.write(Tlv8.METHOD to byteArrayOf(0), Tlv8.SEQ_NO to byteArrayOf(1)), "_pwTy" to 1)
            expectState(reply, 2)
            PendingPairing(required(reply, Tlv8.SALT), required(reply, Tlv8.PUBLIC_KEY))
        }

    /** PS M3–M6. Returns new credentials only after the TV's proof and setup signature verify. */
    fun finishPairing(
        pending: PendingPairing,
        pin: String,
        displayName: String,
    ): CompanionCredentials =
        guarded {
            val srp = CompanionCrypto.PairSetup(pin, pending.salt, pending.serverPublic)
            val proofReply =
                exchangeAuth(
                    PS_NEXT,
                    Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.clientProof),
                    "_pwTy" to 1,
                )
            expectState(proofReply, 4)
            val key = srp.verifyServer(required(proofReply, Tlv8.PROOF))
            val seed = CompanionCrypto.seed()
            val clientId = UUID.randomUUID().toString().toByteArray(Charsets.US_ASCII)
            try {
                val encryptKey = CompanionCrypto.hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", key)
                val controllerX = CompanionCrypto.hkdf("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", key)
                val accessoryX = CompanionCrypto.hkdf("Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", key)
                val publicKey = CompanionCrypto.signingPublic(seed)
                val inner =
                    Tlv8.write(
                        Tlv8.IDENTIFIER to clientId,
                        Tlv8.PUBLIC_KEY to publicKey,
                        Tlv8.SIGNATURE to CompanionCrypto.sign(seed, controllerX + clientId + publicKey),
                        Tlv8.NAME to Opack.pack(mapOf("name" to displayName)),
                    )
                val exchangeReply =
                    exchangeAuth(
                        PS_NEXT,
                        Tlv8.write(
                            Tlv8.SEQ_NO to byteArrayOf(5),
                            Tlv8.ENCRYPTED_DATA to CompanionCrypto.aead(true, encryptKey, PS_MSG05, inner),
                        ),
                        "_pwTy" to 1,
                    )
                expectState(exchangeReply, 6)
                val accessory = Tlv8.read(CompanionCrypto.aead(false, encryptKey, PS_MSG06, required(exchangeReply, Tlv8.ENCRYPTED_DATA)))
                val atvId = required(accessory, Tlv8.IDENTIFIER)
                val ltpk = required(accessory, Tlv8.PUBLIC_KEY)
                CompanionCrypto.verify(ltpk, accessoryX + atvId + ltpk, required(accessory, Tlv8.SIGNATURE))
                encryptKey.fill(0)
                controllerX.fill(0)
                accessoryX.fill(0)
                CompanionCredentials(ltpk, seed.copyOf(), atvId, clientId)
            } finally {
                key.fill(0)
                seed.fill(0)
            }
        }

    /** PV M1–M4, then encrypt everything after with fresh per-connection keys. */
    fun verify(credentials: CompanionCredentials) =
        guarded {
            check(session == null) { "Connection already verified." }
            val seed = CompanionCrypto.seed()
            val publicKey = CompanionCrypto.exchangePublic(seed)
            try {
                val reply = exchangeAuth(PV_START, Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(1), Tlv8.PUBLIC_KEY to publicKey), "_auTy" to 4)
                expectState(reply, 2)
                val serverPublic = required(reply, Tlv8.PUBLIC_KEY)
                val shared = CompanionCrypto.sharedSecret(seed, serverPublic)
                try {
                    val key = CompanionCrypto.hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
                    val device = Tlv8.read(CompanionCrypto.aead(false, key, PV_MSG02, required(reply, Tlv8.ENCRYPTED_DATA)))
                    val identifier = required(device, Tlv8.IDENTIFIER)
                    if (!MessageDigest.isEqual(
                            identifier,
                            credentials.atvId,
                        )
                    ) {
                        throw SecurityException("A different device answered. Scan again or re-pair.")
                    }
                    CompanionCrypto.verify(credentials.ltpk, serverPublic + identifier + publicKey, required(device, Tlv8.SIGNATURE))
                    val proof =
                        Tlv8.write(
                            Tlv8.IDENTIFIER to credentials.clientId,
                            Tlv8.SIGNATURE to CompanionCrypto.sign(credentials.ltsk, publicKey + credentials.clientId + serverPublic),
                        )
                    val done =
                        exchangeAuth(
                            PV_NEXT,
                            Tlv8.write(
                                Tlv8.SEQ_NO to byteArrayOf(3),
                                Tlv8.ENCRYPTED_DATA to CompanionCrypto.aead(true, key, PV_MSG03, proof),
                            ),
                        )
                    expectState(done, 4)
                    key.fill(0)
                    val outKey = CompanionCrypto.hkdf("", "ClientEncrypt-main", shared)
                    val inKey = CompanionCrypto.hkdf("", "ServerEncrypt-main", shared)
                    session = CompanionCrypto.Session(outKey, inKey)
                    outKey.fill(0)
                    inKey.fill(0)
                    startReader()
                } finally {
                    shared.fill(0)
                }
            } finally {
                seed.fill(0)
            }
        }

    /** Reference session startup: _systemInfo then _sessionStart for tvremoteservices. */
    fun startSession(
        info: CompanionClientInfo,
        credentials: CompanionCredentials,
    ) = guarded {
        request(
            "_systemInfo",
            linkedMapOf(
                "_bf" to 0,
                "_cf" to 512,
                "_clFl" to 128,
                "_i" to info.rpId,
                "_idsID" to credentials.clientId,
                "_pubID" to info.deviceId,
                "_sf" to 256,
                "_sv" to "170.18",
                "model" to info.model,
                "name" to info.name,
            ),
        )
        localSid = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
        val content = request("_sessionStart", linkedMapOf("_srvT" to SERVICE, "_sid" to localSid))["_c"] as? Map<*, *>
        remoteSid =
            (content?.get("_sid") as? Long)?.takeIf { it in 0..0xFFFFFFFFL }
                ?: throw ProtocolException("TV did not start a remote session.")
        // tvOS answers tvremoted-backed requests (app list, attention state) only after a TV Remote
        // Client session is registered (pinned pyatv `_tv_rc_session_start`). A device without the
        // service answers with an error, or with nothing at all; HID presses do not need it, so both
        // are tolerated here — while a dead link still fails the requests that follow.
        try {
            request("TVRCSessionStart", linkedMapOf("ProtocolVersionKey" to "1.2"), RC_SESSION_TIMEOUT_MS)
        } catch (_: CompanionRejected) {
            // Not supported on this device.
        } catch (_: ProtocolException) {
            // No answer within the bounded wait; keep the verified session usable.
        }
        // Register the RTI text client so tvOS pushes keyboard-focus events. A device without the
        // service answers with an error; navigation still works, so only that clean failure is ignored.
        try {
            val reply = request("_tiStart", emptyMap())
            textStarted = true
            applyKeyboard((reply["_c"] as? Map<*, *>)?.get("_tiD") as? ByteArray)
        } catch (_: CompanionRejected) {
            keyboardSession = null
        }
        sendEvent("_interest", linkedMapOf("_regEvents" to listOf("_iMC")))
    }

    /** Each down/up waits for the TV's acknowledgment; nothing is retried. */
    fun press(
        command: HidCommand,
        action: PressAction = PressAction.Tap,
    ) = guarded {
        check(remoteSid >= 0) { "Session not started." }

        fun button(down: Boolean) = request("_hidC", linkedMapOf("_hBtS" to if (down) 1 else 2, "_hidC" to command.code))
        when (action) {
            PressAction.Tap -> {
                button(true)
                button(false)
            }
            PressAction.DoubleTap ->
                repeat(2) {
                    button(true)
                    button(false)
                }
            PressAction.Hold -> {
                button(true)
                // Always try to release; if that fails the link closes, ending the session.
                try {
                    Thread.sleep(HOLD_MS)
                } finally {
                    button(false)
                }
            }
        }
    }

    /** Put the TV to sleep: pyatv `CompanionPower.turn_off` sends the release event only, no down/up pair. */
    fun sleep() =
        guarded {
            check(remoteSid >= 0) { "Session not started." }
            request("_hidC", linkedMapOf("_hBtS" to 2, "_hidC" to HidCommand.Sleep.code))
        }

    /** Signed seconds: pyatv uses a float even for whole seconds, including backward skips. */
    fun skip(seconds: Double) =
        guarded {
            require(seconds.isFinite() && seconds != 0.0) { "Skip interval must be finite and non-zero." }
            check(remoteSid >= 0) { "Session not started." }
            if (!skipSupport.allows(seconds)) throw CompanionRejected("Apple TV does not currently support this skip direction.")
            request("_mcc", linkedMapOf("_mcc" to 7, "_skpS" to seconds))
        }

    /** Best-effort polite shutdown of the text and remote sessions, then close. */
    fun stopSession() {
        if (remoteSid >= 0 && session != null && !closed) {
            runCatching { sendEvent("_interest", linkedMapOf("_deregEvents" to listOf("_iMC"))) }
            if (textStarted) runCatching { request("_tiStop", emptyMap(), TEARDOWN_TIMEOUT_MS) }
            runCatching {
                request(
                    "_sessionStop",
                    linkedMapOf("_srvT" to SERVICE, "_sid" to ((remoteSid.toULong() shl 32) or localSid.toULong())),
                    TEARDOWN_TIMEOUT_MS,
                )
            }
        }
        close()
    }

    // Each distinct failure keeps its own message: reject, close, and timeout are told apart.
    @Suppress("ThrowsCount")
    private fun request(
        identifier: String,
        content: Map<String, Any?>,
        timeoutMs: Long = TIMEOUT_MS,
    ): Map<*, *> {
        checkNotNull(session) { "Connection not verified." }
        val id = xid++
        val reply = ArrayBlockingQueue<Reply>(1)
        pending[id] = reply
        return try {
            send(E_OPACK, Opack.pack(linkedMapOf("_i" to identifier, "_t" to REQUEST, "_c" to content, "_x" to id)))
            when (val received = reply.poll(timeoutMs, TimeUnit.MILLISECONDS)) {
                is Reply.Message -> {
                    // Like the reference, an _em error message marks failure; its text is peer data and never shown.
                    if ("_em" in received.message) {
                        throw CompanionRejected("Apple TV rejected the ${identifier.trimStart('_')} request.")
                    }
                    received.message
                }
                Reply.Closed -> throw ProtocolException("Connection closed before the Apple TV answered the request.")
                null -> throw ProtocolException("Apple TV did not answer the ${identifier.trimStart('_')} request.")
            }
        } finally {
            pending.remove(id)
        }
    }

    private fun sendEvent(
        identifier: String,
        content: Map<String, Any?>,
    ) {
        checkNotNull(session) { "Connection not verified." }
        send(E_OPACK, Opack.pack(linkedMapOf("_i" to identifier, "_t" to EVENT, "_c" to content, "_x" to xid++)))
    }

    /** Launchable apps the TV reports; a snapshot from the TV, not a fixed set. */
    fun appList(): List<AppleTvApp> =
        guarded {
            check(remoteSid >= 0) { "Session not started." }
            appListFrom(request("FetchLaunchableApplicationsEvent", emptyMap()))
        }

    /** Open one app by bundle id (pyatv also takes a URL scheme via `_urlS`; not used here). */
    fun launchApp(bundleId: String) =
        guarded {
            check(remoteSid >= 0) { "Session not started." }
            require(bundleId.isNotBlank()) { "No app selected." }
            request("_launchApp", linkedMapOf("_bundleID" to bundleId))
        }

    /** Replace the tvOS keyboard text from the focused RTI session: clear, then insert. */
    fun typeText(text: String) =
        guarded {
            val current = keyboardSession ?: throw CompanionRejected("Apple TV is not showing a text field.")
            sendEvent("_tiC", linkedMapOf("_tiV" to 1, "_tiD" to NskArchiver.clearText(current.uuid)))
            if (text.isNotEmpty()) {
                sendEvent("_tiC", linkedMapOf("_tiV" to 1, "_tiD" to NskArchiver.insertText(current.uuid, text)))
            }
        }

    private fun startReader() {
        if (reader != null) return
        reader =
            Thread { readLoop() }.apply {
                isDaemon = true
                name = "companion-reader"
                start()
            }
    }

    /** Correlate responses by xid and forward push events; the reader owns every post-verify read. */
    private fun readLoop() {
        runCatching {
            while (!closed) {
                val (type, payload) = receive(0L)
                if (type == E_OPACK) {
                    val message = Opack.unpack(payload) as? Map<*, *>
                    if (message != null) {
                        when ((message["_t"] as? Number)?.toLong()) {
                            RESPONSE.toLong() ->
                                (message["_x"] as? Number)?.toLong()?.let { pending.remove(it)?.offer(Reply.Message(message)) }
                            EVENT.toLong() ->
                                (message["_i"] as? String)?.let { name ->
                                    handleEvent(name, message["_c"] as? Map<*, *> ?: emptyMap<Any?, Any?>())
                                }
                        }
                    }
                }
            }
        }.onFailure { failure ->
            // A swallowed decode/framing failure looks like a plain close to callers, so say why.
            if (!closed) Log.w(LOG_TAG, "Companion reader stopped: ${failure.javaClass.simpleName}: ${failure.message}")
        }
        runCatching { close() }
    }

    private fun handleEvent(
        identifier: String,
        content: Map<*, *>,
    ) {
        when (identifier) {
            "_iMC" ->
                synchronized(this) {
                    if (!closed) {
                        skipSupport = CompanionSkipSupport.fromFlags(content["_mcF"])
                        skipListener?.invoke(skipSupport)
                    }
                }
            "_tiStarted" -> applyKeyboard(content["_tiD"] as? ByteArray)
            "_tiStopped" -> {
                keyboardSession = null
                keyboardListener?.invoke(CompanionKeyboardState(false, ""))
            }
        }
    }

    private fun applyKeyboard(archive: ByteArray?) {
        val found = archive?.let { runCatching { NskArchiver.textSession(it) }.getOrNull() } ?: return
        keyboardSession = found
        keyboardListener?.invoke(CompanionKeyboardState(true, found.text))
    }

    // A strict handshake validates every frame; each malformed reply keeps its own message.
    @Suppress("ThrowsCount")
    private fun exchangeAuth(
        type: Int,
        pairingData: ByteArray,
        vararg extra: Pair<String, Any>,
    ): Map<Int, ByteArray> {
        // pyatv's send_opack also adds an _x to auth frames.
        send(
            type,
            Opack.pack(
                linkedMapOf<String, Any>("_pd" to pairingData).apply {
                    putAll(extra)
                    put("_x", xid++)
                },
            ),
        )
        val expected = if (type == PS_START || type == PS_NEXT) PS_NEXT else PV_NEXT
        val deadline = System.nanoTime() + TIMEOUT_NS
        repeat(MAX_FRAMES) {
            val (frameType, payload) = receive(deadline)
            if (frameType != expected) return@repeat
            val message = Opack.unpack(payload) as? Map<*, *> ?: throw ProtocolException("Unexpected pairing reply.")
            val tlv = Tlv8.read(message["_pd"] as? ByteArray ?: throw ProtocolException("Pairing reply has no pairing data."))
            tlv[Tlv8.ERROR]?.let { throw CompanionRejected(pairingError(it.firstOrNull()?.toInt() ?: 0, type)) }
            return tlv
        }
        throw ProtocolException("Apple TV did not answer the pairing step.")
    }

    private fun send(
        type: Int,
        payload: ByteArray,
    ) {
        check(!closed) { "Connection closed. Reconnect before sending." }
        val crypto = session
        val length = payload.size + if (crypto != null && payload.isNotEmpty()) TAG_LENGTH else 0
        if (length > MAX_FRAME) throw ProtocolException("Message too large.")
        val header = byteArrayOf(type.toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())
        synchronized(sendLock) {
            output.write(header)
            output.write(if (crypto != null && payload.isNotEmpty()) crypto.encrypt(payload, header) else payload)
            output.flush()
        }
    }

    private fun receive(deadline: Long): Pair<Int, ByteArray> {
        val header = readFully(input, socket, 4, deadline)
        val length = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        if (length > MAX_FRAME) throw ProtocolException("Apple TV message too large.")
        val payload = readFully(input, socket, length, deadline)
        val crypto = session
        return (header[0].toInt() and 0xFF) to if (crypto != null && length > 0) crypto.decrypt(payload, header) else payload
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            skipSupport = CompanionSkipSupport()
            skipListener?.invoke(skipSupport)
        }
        // Wake any in-flight request immediately once the reader can no longer answer.
        pending.values.forEach { it.offer(Reply.Closed) }
        keyboardSession = null
        textStarted = false
        session?.close()
        runCatching {
            socket?.close() ?: run {
                input.close()
                output.close()
            }
        }
    }

    companion object {
        private const val PS_START = 3
        private const val PS_NEXT = 4
        private const val PV_START = 5
        private const val PV_NEXT = 6
        private const val E_OPACK = 8
        private const val EVENT = 1
        private const val REQUEST = 2
        private const val RESPONSE = 3
        private const val TAG_LENGTH = 16
        private const val MAX_FRAME = 64 * 1024
        private const val MAX_FRAMES = 64
        private const val SERVICE = "com.apple.tvremoteservices"
        private const val HOLD_MS = 1000L // pyatv _press_button default delay
        private const val TIMEOUT_MS = 5_000L

        /** The TV RC session registration is optional; do not stall a connect that ignores it. */
        private const val RC_SESSION_TIMEOUT_MS = 2_000L
        private const val TEARDOWN_TIMEOUT_MS = 1_000L
        private const val TIMEOUT_NS = 5_000_000_000L
        private val PS_MSG05 = "PS-Msg05".toByteArray(Charsets.US_ASCII)
        private val PS_MSG06 = "PS-Msg06".toByteArray(Charsets.US_ASCII)
        private val PV_MSG02 = "PV-Msg02".toByteArray(Charsets.US_ASCII)
        private val PV_MSG03 = "PV-Msg03".toByteArray(Charsets.US_ASCII)

        // Close the half-open socket if connect or the link hand-off fails, then rethrow.
        @Suppress("TooGenericExceptionCaught")
        fun open(
            address: InetAddress,
            port: Int,
        ): CompanionLink {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, port), 5000)
                return CompanionLink(socket.getInputStream(), socket.getOutputStream(), socket)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }

        private fun required(
            tlv: Map<Int, ByteArray>,
            tag: Int,
        ) = tlv[tag] ?: throw ProtocolException("Pairing reply is missing data.")

        private fun expectState(
            tlv: Map<Int, ByteArray>,
            state: Int,
        ) {
            val actual = tlv[Tlv8.SEQ_NO]
            if (actual == null || actual.size != 1 || actual[0].toInt() != state) throw ProtocolException("Pairing reply out of order.")
        }

        private fun pairingError(
            code: Int,
            step: Int,
        ) = when (code) {
            2 ->
                if (step == PV_NEXT || step == PV_START) {
                    "Apple TV no longer accepts this pairing. Forget it and pair again."
                } else {
                    "Wrong PIN. Start pairing again for a new PIN."
                }
            3 -> "Apple TV is refusing pairing attempts for now. Wait a minute, then pair again."
            4 -> "Apple TV has too many paired remotes. Remove one in its settings, then pair again."
            5 -> "Too many failed attempts. Restart pairing later."
            6 -> "Apple TV is not accepting pairing right now (already pairing with another device?)."
            7 -> "Apple TV is busy. Retry in a moment."
            else -> "Apple TV rejected pairing (code $code)."
        }
    }
}

/**
 * Maps a `FetchLaunchableApplicationsEvent` reply to sorted apps: `_c` is bundle id to displayed name.
 * An entry without a usable id cannot be launched and is dropped; a missing name falls back to the id.
 */
internal fun appListFrom(reply: Map<*, *>): List<AppleTvApp> {
    val content = reply["_c"] as? Map<*, *> ?: throw ProtocolException("Apple TV did not return an app list.")
    return content.entries
        .mapNotNull { (id, name) ->
            val bundleId = (id as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AppleTvApp(bundleId, (name as? String)?.takeIf { it.isNotBlank() } ?: bundleId)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, AppleTvApp::name))
}

/** Reads exactly [count] bytes, keeping the socket timeout inside [deadline]; fails on close or timeout. */
private fun readFully(
    input: InputStream,
    socket: Socket?,
    count: Int,
    deadline: Long,
): ByteArray {
    val buffer = ByteArray(count)
    var read = 0
    while (read < count) {
        if (deadline == 0L) {
            // The reader waits for the next frame until close() breaks the socket.
            socket?.soTimeout = 0
        } else {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) throw SocketTimeoutException("Apple TV timed out.")
            socket?.soTimeout = remaining.toInt().coerceAtLeast(1)
        }
        val n = input.read(buffer, read, count - read)
        if (n < 0) throw IOException("Apple TV closed the connection.")
        read += n
    }
    return buffer
}

// A failed frame desyncs the byte stream, so any failure closes the link before rethrowing.
@Suppress("TooGenericExceptionCaught")
private inline fun <T> CompanionLink.guarded(block: () -> T): T {
    check(!closed) { "Connection closed. Reconnect before sending." }
    return try {
        block()
    } catch (e: Exception) {
        close()
        throw e
    }
}
