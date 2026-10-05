package fi.goodconsulting.kaukosaadin.device.companion

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One Apple TV, one operation at a time; never queues or replays a press. */
@SuppressLint("UseKtx") // commit() results are checked; KTX edit {} would discard them.
@Suppress("TooManyFunctions") // Pairing, commands and session ownership share one lock and credential store.
class CompanionClient(
    context: Context,
) {
    data class Result(
        val ok: Boolean,
        val message: String,
    )

    private val appContext = context.applicationContext

    // ponytail: one saved Apple TV, mirroring LgClient; add a list when more are needed.
    private val prefs = appContext.getSharedPreferences("companion", Context.MODE_PRIVATE)
    private val lock = Mutex()

    // Owned exclusively under lock, including lifecycle shutdown.
    private var link: CompanionLink? = null
    private val mutableStatus =
        MutableStateFlow(
            Result(
                false,
                if (prefs.contains("credentials")) {
                    "Apple TV paired. Open the remote to connect."
                } else {
                    "Scan, choose your Apple TV, then pair with the PIN it shows."
                },
            ),
        )
    val status = mutableStatus.asStateFlow()
    private val mutablePaired = MutableStateFlow(prefs.contains("credentials"))
    val paired = mutablePaired.asStateFlow()
    private val mutableAwaitingPin = MutableStateFlow(false)
    val awaitingPin = mutableAwaitingPin.asStateFlow()
    private val mutableKeyboard = MutableStateFlow<CompanionKeyboardState?>(null)

    /** Non-null while the TV's on-screen keyboard is focused; the UI mirrors typed text to it. */
    val keyboard = mutableKeyboard.asStateFlow()

    private val mutableApps = MutableStateFlow(emptyList<AppleTvApp>())

    /** Last app list the TV reported; empty until [appList] succeeds. A snapshot, not a fixed set. */
    val apps = mutableApps.asStateFlow()

    @Volatile private var pendingPin: Channel<String>? = null
    val name get() = prefs.getString("name", "")!!

    // Boundary for every Apple TV command: unknown failures become a status message, never peer text.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun operation(block: suspend () -> Result): Result =
        withContext(Dispatchers.IO) {
            if (!lock.tryLock()) return@withContext Result(false, "Apple TV busy; press not queued. Try again after completion.")
            try {
                val result =
                    try {
                        block()
                    } catch (_: TimeoutCancellationException) {
                        Result(false, "PIN entry timed out. Pair again for a new PIN.")
                    } catch (e: CancellationException) {
                        closeSession()
                        mutableStatus.value = Result(false, "Apple TV operation cancelled.")
                        throw e
                    } catch (e: Exception) {
                        closeSession()
                        // Our own failure text only (see CompanionLink); never peer text, keys or secrets.
                        Log.w(LOG_TAG, "Apple TV operation failed: ${e.javaClass.simpleName}: ${e.message}")
                        // Messages are ours (see CompanionLink); never peer text, keys or raw exception detail.
                        Result(
                            false,
                            when (e) {
                                is CompanionRejected, is SecurityException, is IllegalArgumentException, is IllegalStateException ->
                                    e.message ?: "Apple TV rejected the request."
                                is ProtocolException -> "Unexpected Apple TV reply. Reconnect; if it repeats, forget and pair again."
                                else -> "Apple TV unreachable or timed out. Wake it with its own remote, check same Wi-Fi, scan again."
                            },
                        )
                    }
                mutableStatus.value = result
                result
            } finally {
                lock.unlock()
            }
        }

    /** Pair-setup with the PIN shown on the TV, then prove the saved pairing with a fresh pair-verify. */
    suspend fun pair(device: CompanionDiscovery.Device) =
        operation {
            closeSession()
            mutableStatus.value = Result(false, "Connecting to ${device.name}…")
            val credentials =
                CompanionLink.open(device.address, device.port).use { link ->
                    val pending = link.startPairing()
                    link.finishPairing(pending, awaitPin(), DISPLAY_NAME)
                }
            try {
                CompanionLink.open(device.address, device.port).use { it.verify(credentials) }
                writeCredentials(credentials)
            } finally {
                credentials.wipe()
            }
            check(
                prefs
                    .edit()
                    .putString("host", device.address.hostAddress)
                    .putInt("port", device.port)
                    .putString("name", device.name)
                    .commit(),
            ) { "Could not save Apple TV. Pair again." }
            mutablePaired.value = true
            Result(true, "Paired with ${device.name} and verified. Navigation buttons are ready.")
        }

    suspend fun connect() =
        operation {
            mutableStatus.value = Result(false, "Connecting to Apple TV…")
            session()
            Result(true, "Connected to Apple TV. Navigation buttons are ready.")
        }

    /** Wait for any in-flight press before closing; never race the link's cipher counters. */
    suspend fun disconnect() =
        withContext(Dispatchers.IO) {
            lock.withLock {
                closeSession()
                mutableStatus.value = Result(false, "Apple TV disconnected.")
            }
        }

    // Best-effort polite teardown so the TV drops its remote/text sessions; close() is the fallback.
    private fun closeSession() {
        val current = link
        link = null
        current?.let { runCatching { it.stopSession() } }
        mutableKeyboard.value = null
    }

    private fun session(): CompanionLink {
        link?.let { return it }
        val host = prefs.getString("host", null)
        val credentials = (if (host == null) null else readCredentials()) ?: error("Pair with the Apple TV first.")
        try {
            val opened = CompanionLink.open(InetAddress.getByName(host), prefs.getInt("port", 0))
            try {
                opened.keyboardListener = { mutableKeyboard.value = it }
                opened.verify(credentials)
                opened.startSession(clientInfo(), credentials)
                link = opened
                return opened
            } finally {
                if (link !== opened) opened.close()
            }
        } finally {
            credentials.wipe()
        }
    }

    suspend fun press(
        command: HidCommand,
        action: PressAction = PressAction.Tap,
    ) = operation {
        session().press(command, action)
        val label =
            when (action) {
                PressAction.Tap -> command.name
                PressAction.DoubleTap -> "${command.name} double tap"
                PressAction.Hold -> "${command.name} hold"
            }
        Result(true, "$label acknowledged by Apple TV; on-screen result NOT confirmed.")
    }

    /** Refresh the launchable-app list; the TV reports it only while awake and connected. */
    suspend fun appList() =
        operation {
            val apps = session().appList()
            mutableApps.value = apps
            Result(true, "Apple TV reported ${apps.size} launchable app(s).")
        }

    /** Ask the TV to open one app; it lands on the app's own home screen, not specific content. */
    suspend fun launchApp(app: AppleTvApp) =
        operation {
            session().launchApp(app.bundleId)
            Result(true, "${app.name} launch acknowledged by Apple TV; on-screen result NOT confirmed.")
        }

    /** Mirror the phone's text field to the TV; waits for the lock instead of dropping edits. */
    @Suppress("TooGenericExceptionCaught") // Any link failure must close the session, never leave it half-open.
    suspend fun sendText(text: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val current = link ?: return@withLock
                try {
                    current.typeText(text)
                } catch (e: CancellationException) {
                    closeSession()
                    throw e
                } catch (e: Exception) {
                    closeSession()
                    // Messages are ours (see CompanionLink); never peer text or raw exception detail.
                    val known = e is CompanionRejected || e is ProtocolException
                    mutableStatus.value = Result(false, if (known) e.message ?: TEXT_FAILED else TEXT_UNREACHABLE)
                }
            }
        }

    suspend fun forget() =
        operation {
            closeSession()
            check(prefs.edit().clear().commit()) { "Could not forget pairing." }
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
            mutablePaired.value = false
            Result(true, "Pairing forgotten here. Also remove \"$DISPLAY_NAME\" in Apple TV Settings › Remotes and Devices.")
        }

    fun submitPin(pin: String): Result {
        if (!pin.matches(Regex("[0-9]{4}"))) return Result(false, "Enter the four-digit PIN shown on the Apple TV.")
        val submitted = pendingPin?.trySend(pin)?.isSuccess == true
        return Result(submitted, if (submitted) "PIN submitted; checking with Apple TV…" else "No active PIN request. Pair again.")
    }

    fun cancelPairing() {
        pendingPin?.close()
    }

    private suspend fun awaitPin(): String {
        val input = Channel<String>(capacity = 1)
        pendingPin = input
        mutableAwaitingPin.value = true
        mutableStatus.value = Result(false, "Enter the PIN shown on the Apple TV within 90 seconds.")
        try {
            return withTimeout(PAIRING_TIMEOUT_MS) {
                try {
                    input.receive()
                } catch (_: ClosedReceiveChannelException) {
                    error("Pairing cancelled.")
                }
            }
        } finally {
            mutableAwaitingPin.value = false
            pendingPin = null
            input.cancel()
        }
    }

    /** Stable, non-secret per-install identity for _systemInfo (pyatv generates the same shapes). */
    private fun clientInfo(): CompanionClientInfo {
        val random = SecureRandom()
        val rpId = prefs.getString("rpId", null) ?: ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val deviceId = prefs.getString("deviceId", null) ?: ByteArray(6).also(random::nextBytes).joinToString(":") { "%02X".format(it) }
        prefs
            .edit()
            .putString("rpId", rpId)
            .putString("deviceId", deviceId)
            .apply()
        return CompanionClientInfo(DISPLAY_NAME, rpId, deviceId)
    }

    // Same Keystore pattern as LgClient, separate alias; allowBackup=false keeps it on this phone.
    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey ?: KeyGenerator
            .getInstance("AES", "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
    }

    private fun writeCredentials(credentials: CompanionCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        val plain = credentials.encode().toByteArray(Charsets.US_ASCII)
        val encrypted =
            try {
                cipher.iv + cipher.doFinal(plain)
            } finally {
                plain.fill(0)
            }
        check(prefs.edit().putString("credentials", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "Pairing could not be saved; pair again."
        }
    }

    private fun readCredentials(): CompanionCredentials? {
        val encoded = prefs.getString("credentials", null) ?: return null
        val plain =
            try {
                val data = Base64.decode(encoded, Base64.NO_WRAP)
                Cipher
                    .getInstance("AES/GCM/NoPadding")
                    .apply {
                        init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
                    }.doFinal(data.copyOfRange(12, data.size))
            } catch (_: Exception) {
                error("Saved Apple TV pairing cannot be decrypted. Forget it, then pair again.")
            }
        return try {
            CompanionCredentials.decode(String(plain, Charsets.US_ASCII))
        } finally {
            plain.fill(0)
        }
    }

    companion object {
        const val DISPLAY_NAME = "Kaukosaadin"

        /** Field diagnostics only: our own failure text, never peer data or secrets. */
        private const val LOG_TAG = "Kaukosaadin"
        private const val KEY_ALIAS = "companion-pairing"
        private const val TEXT_FAILED = "Apple TV could not take the text. Reconnect and try again."
        private const val TEXT_UNREACHABLE = "Apple TV unreachable while sending text. Reconnect and try again."

        /** The Apple TV only shows the PIN screen for this long. */
        private const val PAIRING_TIMEOUT_MS = 90_000L
    }
}
