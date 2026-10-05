package fi.goodconsulting.kaukosaadin.device

import android.content.Context
import android.net.wifi.WifiManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lgtvremote.discovery.TVDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.channels.Channel as PinChannel

// ponytail: one class per paired TV — settings, pairing, wake and status share one stored TV;
// split persistence out when a second TV or transport lands.

/** One operation at a time; never queues or replays navigation across reconnection. */
@Suppress("TooManyFunctions")
class LgClient(
    context: Context,
) {
    data class Result(
        val ok: Boolean,
        val message: String,
    )

    private val appContext = context.applicationContext

    // ponytail: one saved TV; add a device list when multiple remotes are needed.
    private val prefs = appContext.getSharedPreferences("lg", Context.MODE_PRIVATE)
    private val mutableDevices = MutableStateFlow<List<TVDiscovery.DiscoveredTV>>(emptyList())
    val devices = mutableDevices.asStateFlow()
    private val lock = Mutex()
    private val mutableStatus =
        MutableStateFlow(
            Result(
                false,
                if (prefs.contains("fingerprint")) {
                    "TV not connected. Tap Connect TV with the TV awake."
                } else {
                    "Add a TV, inspect its certificate and save approved setup."
                },
            ),
        )
    val status = mutableStatus.asStateFlow()

    // Registration verified this run, not a live socket or TV power-state report.
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val mutableAwaitingPin = MutableStateFlow(false)
    val awaitingPin = mutableAwaitingPin.asStateFlow()

    @Volatile private var pendingPin: PinChannel<String>? = null
    val host get() = prefs.getString("host", "")!!
    val name get() = LgProtocol.tvName(prefs.getString("name", "")!!)
    val mac get() = prefs.getString("mac", "")!!
    val broadcast get() = prefs.getString("broadcast", "")!!
    val fingerprint get() = prefs.getString("fingerprint", "")!!

    // Boundary for every LG command: unknown failures become a status message, never peer text.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun operation(block: suspend () -> Result): Result =
        withContext(Dispatchers.IO) {
            if (!lock.tryLock()) return@withContext Result(false, "LG busy; command not queued. Try again after completion.")
            try {
                mutableStatus.value = Result(false, "LG working… Pairing may display a PIN on the TV.")
                val result =
                    try {
                        block()
                    } catch (_: TimeoutCancellationException) {
                        Result(false, "PIN entry timed out. Retry Connect / pair LG to request a new code.")
                    } catch (e: CancellationException) {
                        mutableStatus.value = Result(false, "LG operation cancelled. Retry Connect when ready.")
                        throw e
                    } catch (e: Exception) {
                        // Never include peer responses, pairing keys or exception text in status/logs.
                        Result(
                            false,
                            when (e) {
                                is CertificateException, is SSLException ->
                                    "Certificate rejected. Inspect and explicitly approve the TV certificate; " +
                                        "never downgrade to ws."
                                is IllegalArgumentException -> e.message ?: "Invalid setup."
                                is IllegalStateException -> e.message ?: "TV rejected request; forget and re-pair."
                                else -> "LG unreachable or timed out. Wake TV, check Wi-Fi/LAN permission and address, then reconnect."
                            },
                        )
                    }
                mutableStatus.value = result
                result
            } finally {
                lock.unlock()
            }
        }

    suspend fun discover() =
        operation {
            mutableDevices.value = emptyList()
            mutableStatus.value = Result(false, "Searching the LAN for awake LG TVs…")
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val multicast = wifi.createMulticastLock("lg-discovery").apply { setReferenceCounted(false) }
            try {
                multicast.acquire()
                mutableDevices.value =
                    TVDiscovery().scanNetwork().filter {
                        runCatching { LgProtocol.ipv4(it.ip) }.isSuccess
                    }
            } finally {
                if (multicast.isHeld) multicast.release()
            }
            val count = mutableDevices.value.size
            Result(
                count > 0,
                if (count > 0) {
                    "Found $count LG TV(s). Select one; discovery does not establish trust."
                } else {
                    "No LG TVs replied. Turn TV on, check same Wi-Fi/subnet and router isolation, retry or use saved/manual setup."
                },
            )
        }

    suspend fun save(
        address: String,
        pin: String,
        displayName: String,
    ) = operation {
        val normalizedPin = LgProtocol.pairingFingerprint(address, pin)
        val addressChanged = host != address
        val changed = addressChanged || fingerprint != normalizedPin
        check(
            prefs
                .edit()
                .putString("host", address)
                .putString("fingerprint", normalizedPin)
                .putString("name", LgProtocol.tvName(displayName))
                .apply {
                    if (changed) remove("key")
                    if (addressChanged) {
                        remove("mac")
                        remove("broadcast")
                    }
                }.commit(),
        ) { "Could not save setup. Try again." }
        if (changed) mutableReady.value = false
        Result(true, "Setup saved. Connect with the TV awake to pair. Wake settings are optional.")
    }

    suspend fun saveName(
        address: String,
        displayName: String,
    ) = operation {
        check(address == host && host.isNotEmpty()) { "Save this TV's setup before renaming it." }
        check(prefs.edit().putString("name", LgProtocol.tvName(displayName)).commit()) { "Could not save TV name. Try again." }
        Result(true, "TV name saved. Pairing is unchanged.")
    }

    suspend fun remove() =
        operation {
            check(prefs.edit().clear().commit()) { "Could not remove TV. Try again." }
            mutableReady.value = false
            Result(true, "TV removed. Add a TV to start again.")
        }

    suspend fun saveWake(
        address: String,
        hardwareAddress: String,
        subnetBroadcast: String,
    ) = operation {
        check(address == host && fingerprint.isNotEmpty()) { "Save this TV's approved setup before saving wake settings." }
        LgProtocol.magicPacket(hardwareAddress)
        LgProtocol.ipv4(subnetBroadcast)
        check(
            prefs
                .edit()
                .putString("mac", hardwareAddress)
                .putString("broadcast", subnetBroadcast)
                .commit(),
        ) {
            "Could not save wake settings. Try again."
        }
        Result(true, "Wake settings saved. Pairing and navigation do not depend on these settings.")
    }

    /** Captures a certificate then ABORTS the handshake: no credentials sent to an untrusted peer. */
    suspend fun inspect(address: String): String? {
        var seen: String? = null
        operation {
            LgProtocol.ipv4(address)
            val trust =
                trustManager { cert ->
                    seen = digest(cert)
                    throw CertificateException("Inspection only")
                }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            try {
                (ssl.socketFactory.createSocket() as SSLSocket).use {
                    it.connect(InetSocketAddress(address, CONTROL_PORT), SOCKET_TIMEOUT_MS)
                    it.soTimeout = SOCKET_TIMEOUT_MS
                    it.startHandshake()
                }
            } catch (e: SSLException) {
                if (seen == null) throw e
            }
            Result(true, "Certificate inspected, not trusted. Verify TV identity on a trusted LAN before approving.")
        }
        return seen
    }

    suspend fun forget() =
        operation {
            check(
                prefs
                    .edit()
                    .remove("key")
                    .remove("fingerprint")
                    .commit(),
            ) { "Could not forget pairing." }
            mutableReady.value = false
            Result(true, "Pairing and certificate forgotten. Inspect, approve, then connect again.")
        }

    suspend fun connect() =
        operation {
            mutableReady.value = false
            session(null).also { mutableReady.value = it.ok }
        }

    fun submitPin(pin: String): Result {
        try {
            LgProtocol.pinRequest(pin)
        } catch (e: IllegalArgumentException) {
            return Result(false, e.message ?: "Invalid PIN.")
        }
        val submitted = pendingPin?.trySend(pin)?.isSuccess == true
        return Result(
            submitted,
            if (submitted) {
                "PIN submitted; waiting for TV registration."
            } else {
                "No active PIN request. Retry Connect / pair LG."
            },
        )
    }

    fun cancelPairing() {
        pendingPin?.close()
    }

    private suspend fun awaitPin(): String {
        currentCoroutineContext().ensureActive()
        val input = PinChannel<String>(capacity = 1)
        pendingPin = input
        mutableAwaitingPin.value = true
        mutableStatus.value = Result(false, "Enter the PIN displayed on the TV within 90 seconds.")
        try {
            return withTimeout(PIN_TIMEOUT_MS) {
                try {
                    input.receive()
                } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                    error("PIN pairing cancelled. Retry Connect / pair LG when ready.")
                }
            }
        } finally {
            mutableAwaitingPin.value = false
            pendingPin = null
            input.cancel()
        }
    }

    suspend fun send(action: LgProtocol.Action) =
        operation {
            mutableReady.value = false
            if (action == LgProtocol.Action.Wake) {
                check(mac.isNotEmpty() && broadcast.isNotEmpty()) { "Save the TV's MAC and subnet broadcast in Wake settings first." }
                val packet = LgProtocol.magicPacket(mac)
                val target = InetAddress.getByName(LgProtocol.ipv4(broadcast))
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.send(DatagramPacket(packet, packet.size, target, WAKE_PORT))
                }
                Result(true, "Wake packet sent; TV wake NOT confirmed. Wait for TV, then Connect.")
            } else {
                session(action).also { mutableReady.value = it.ok }
            }
        }

    // ponytail: reconnect per press adds TLS latency; reuse a live session only if measured too slow.
    private suspend fun session(action: LgProtocol.Action?): Result {
        val address = LgProtocol.ipv4(host)
        check(fingerprint.isNotEmpty()) { "Inspect and approve the TV certificate first." }
        val savedKey = readKey()
        check(action == null || savedKey != null) { "Connect / pair with the TV awake before sending navigation." }
        val http = pinnedClient(address)
        try {
            Channel(http, "wss://$address:$CONTROL_PORT/").use { control ->
                awaitRegistration(control, savedKey, action)
                if (action == null) return Result(true, "LG registered; connection verified, not TV power or selection.")
                val path = awaitPointerPath(control)
                Channel(http, LgProtocol.pointerUrl(path, address)).use { pointer ->
                    pointer.awaitOpen()
                    pointer.send(LgProtocol.button(action))
                    // Close is ordered after queued text; its peer acknowledgment bounds the flush.
                    pointer.finish()
                }
                return Result(true, "${action.name} sent; selection movement NOT confirmed (no button acknowledgment).")
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    /**
     * Pinned TLS client: the exact leaf-certificate pin replaces CA/hostname checks
     * for the TV's self-signed LAN cert.
     */
    private fun pinnedClient(address: String): OkHttpClient {
        val trust =
            trustManager { cert ->
                if (digest(cert) != fingerprint) throw CertificateException("Certificate changed")
            }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient
            .Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { name, _ -> name == address }
            .connectTimeout(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** Sends registration and drives it to completion, submitting the PIN when the TV asks. */
    private suspend fun awaitRegistration(
        control: Channel,
        savedKey: String?,
        action: LgProtocol.Action?,
    ) {
        control.awaitOpen()
        control.send(LgProtocol.registration(savedKey))
        val timeoutMs = if (action == null) REGISTRATION_TIMEOUT_MS else SOCKET_TIMEOUT_MS.toLong()
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var pinSent = false
        var registered = false
        while (!registered) {
            val reply = LgProtocol.response(control.receive(deadline))
            if (LgProtocol.needsPin(reply, navigation = action != null)) {
                check(!pinSent) { "TV requested PIN again. Retry Connect with a new code." }
                control.send(LgProtocol.pinRequest(awaitPin()))
                pinSent = true
                mutableStatus.value = Result(false, "PIN submitted; waiting for TV registration.")
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SOCKET_TIMEOUT_MS.toLong())
            }
            if (reply.optString("type") == "registered") {
                val key = reply.optJSONObject("payload")?.optString("client-key")
                check(!key.isNullOrBlank()) { "TV did not return pairing material. Forget and re-pair." }
                writeKey(key)
                registered = true
            }
        }
    }

    /** Asks for the pointer-input socket, then waits for the path the TV returns. */
    private suspend fun awaitPointerPath(control: Channel): String {
        control.send(
            JSONObject()
                .put("id", "pointer")
                .put("type", "request")
                .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                .toString(),
        )
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SOCKET_TIMEOUT_MS.toLong())
        while (true) {
            val reply = LgProtocol.response(control.receive(deadline))
            if (reply.optString("id") == "pointer") return reply.getJSONObject("payload").getString("socketPath")
        }
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey("lg-pairing", null) as? SecretKey ?: KeyGenerator
            .getInstance("AES", "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder("lg-pairing", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
    }

    private fun writeKey(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("key", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "Pairing could not be saved; retry pairing."
        }
    }

    // AES-GCM's 128-bit tag and 12-byte IV describe the stored pairing format.
    @Suppress("MagicNumber")
    private fun readKey(): String? {
        val encoded = prefs.getString("key", null) ?: return null
        return try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher =
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
                }
            String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            error("Saved pairing cannot be decrypted. Forget pairing, then reconnect using the TV PIN.")
        }
    }

    private class Channel(
        http: OkHttpClient,
        url: String,
    ) : WebSocketListener(),
        Closeable {
        @Volatile private var failure: Throwable? = null
        private val opened = LinkedBlockingQueue<Boolean>()
        private val events = LinkedBlockingQueue<String>()
        private val closed = LinkedBlockingQueue<Boolean>()
        private val socket = http.newWebSocket(Request.Builder().url(url).build(), this)

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            opened.offer(true)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (text.length > MAX_MESSAGE_CHARS || events.size >= MAX_PENDING_MESSAGES) webSocket.cancel() else events.offer(text)
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            failure = t
            opened.offer(false)
            events.offer("")
            closed.offer(false)
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(code, null)
            events.offer("")
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            closed.offer(code == NORMAL_CLOSE)
        }

        fun awaitOpen() {
            if (opened.poll(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS) != true) throwFailure()
        }

        private fun throwFailure(): Nothing {
            val cause = failure
            if (cause is SSLException) throw cause
            throw java.io.IOException("Connection failed", cause)
        }

        fun send(text: String) {
            if (!socket.send(text)) throw java.io.IOException("Send failed")
        }

        fun receive(deadline: Long): String {
            val text = events.poll((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            if (text.isNullOrEmpty()) throwFailure()
            return text
        }

        fun finish() {
            if (!socket.close(NORMAL_CLOSE, null) || closed.poll(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS) != true) {
                throw java.io.IOException("Delivery uncertain")
            }
        }

        override fun close() {
            socket.cancel()
        }
    }

    companion object {
        private const val CONTROL_PORT = 3001
        private const val WAKE_PORT = 9
        private const val SOCKET_TIMEOUT_MS = 5000
        private const val REGISTRATION_TIMEOUT_MS = 30_000L
        private const val PIN_TIMEOUT_MS = 90_000L
        private const val MAX_MESSAGE_CHARS = 65_536
        private const val MAX_PENDING_MESSAGES = 32
        private const val NORMAL_CLOSE = 1000

        private fun digest(cert: X509Certificate) =
            MessageDigest
                .getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString("") { "%02x".format(it) }

        private fun trustManager(check: (X509Certificate) -> Unit) =
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>,
                    authType: String,
                ): Unit = throw CertificateException()

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>,
                    authType: String,
                ) {
                    if (chain.isEmpty()) throw CertificateException()
                    check(chain[0])
                }
            }
    }
}
