package fi.goodconsulting.kaukosaadin.device.hue

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One saved Hue Bridge, keyed by its [id]. It owns the bridge's stored app key and certificate pin
 * and the setup (link-button pairing). Ticket #8 builds the lighting API client on [http] and [tls];
 * it never needs to touch TLS itself.
 */
internal class HueClient(
    context: Context,
    id: String,
) {
    data class Result(
        val ok: Boolean,
        val message: String,
    )

    /**
     * Everything this bridge keeps on this phone — its address, app key, pin and the operator's
     * favorites — in the one `hue-<id>` file that [forget] removes wholesale. Ticket #11's favorites
     * sit in it for exactly that reason.
     */
    val storage: HueStorage = HuePrefsStorage(context.applicationContext, id)

    private val credentials = HueCredentials(storage, HueCredentials::androidKeystoreKey)
    private val lock = Mutex()
    private val mutableStatus =
        MutableStateFlow(
            Result(
                false,
                if (credentials.applicationKey.isNullOrEmpty()) {
                    "Choose the bridge and press its link button to pair."
                } else {
                    "Bridge paired; the app key is stored on this phone."
                },
            ),
        )

    /** The current setup message, shown by the bridge's setup and remote screens. */
    val status: StateFlow<Result> = mutableStatus.asStateFlow()

    /** The trust layer #8 configures its API client with; there is no trust-all alternative. */
    val tls = HueTls(credentials)

    val host: String? get() = credentials.host
    val applicationKey: String? get() = credentials.applicationKey
    val pin: String? get() = credentials.pin

    /** An OkHttp client locked to [host]; [tune] lets a caller set its own timeouts (e.g. the event stream). */
    fun http(
        host: String,
        tune: OkHttpClient.Builder.() -> Unit = {},
    ): OkHttpClient = tls.configure(host).apply(tune).build()

    /**
     * One link-button pairing attempt against [address]. Error 101 leaves the operator to press the
     * button and call this again; there is no automatic retry loop.
     */
    suspend fun pair(address: String): Result =
        operation {
            HueProtocol.ipv4(address)
            val call =
                http(address) { retryOnConnectionFailure(false) }.newCall(
                    Request
                        .Builder()
                        .url("https://$address${HueProtocol.PAIRING_PATH}")
                        .post(HueProtocol.pairingBody().toRequestBody(JSON))
                        .build(),
                )
            val body = call.execute().use { it.body?.string().orEmpty() }
            when (val result = HueProtocol.pairingResult(body)) {
                is HuePairingResult.Paired -> {
                    check(credentials.savePairing(address, result.applicationKey)) {
                        "The bridge paired, but the app key could not be saved. Try again."
                    }
                    Result(true, "Bridge paired; the app key is stored on this phone.")
                }
                HuePairingResult.LinkButtonNotPressed ->
                    Result(false, "Waiting for the link button: press it on the bridge, then tap Pair again.")
                is HuePairingResult.Rejected -> Result(false, result.message)
            }
        }

    /** Forgets this bridge: its address, app key and certificate pin. The saved entry is the caller's. */
    suspend fun forget(): Result =
        operation {
            check(credentials.forget()) { "Could not forget the bridge. Try again." }
            Result(true, "Bridge forgotten.")
        }

    // Boundary for every bridge operation: unknown failures become a status message, never peer text.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun operation(block: suspend () -> Result): Result =
        withContext(Dispatchers.IO) {
            if (!lock.tryLock()) return@withContext Result(false, "Bridge busy; try again after the current operation.")
            try {
                val result =
                    try {
                        block()
                    } catch (e: CancellationException) {
                        mutableStatus.value = Result(false, "Hue operation cancelled. Try again.")
                        throw e
                    } catch (e: Exception) {
                        Result(false, message(e))
                    }
                mutableStatus.value = result
                result
            } finally {
                lock.unlock()
            }
        }

    // Our own failure text only; never the app key, the pin or peer responses. The TLS and
    // unreachable cases are the same text the lighting screen shows, so [HueErrors] owns them.
    private fun message(e: Exception): String =
        when (e) {
            is IllegalArgumentException, is IllegalStateException -> e.message ?: "Invalid bridge setup."
            else -> HueErrors.transportFailure(e)
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
