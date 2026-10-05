package fi.goodconsulting.kaukosaadin.device.hue

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONException
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The app key and its optional entertainment client key, as the bridge returned them. */
internal data class HuePairing(
    val applicationKey: String,
    val clientKey: String?,
)

/** The on-device bytes of a [HuePairing]; the app key survives a restart through this. */
internal object HuePairingCodec {
    fun encode(pairing: HuePairing): String =
        JSONObject()
            .put("applicationKey", pairing.applicationKey)
            .apply { pairing.clientKey?.let { put("clientKey", it) } }
            .toString()

    fun decode(value: String): HuePairing? =
        try {
            val json = JSONObject(value)
            val applicationKey = json.optString("applicationKey").takeIf { it.isNotBlank() } ?: return null
            HuePairing(applicationKey, json.optString("clientKey").takeIf { it.isNotBlank() })
        } catch (_: JSONException) {
            null
        }
}

/**
 * The name-value seam [HueCredentials] sits on. The app binds it to a SharedPreferences file; tests
 * bind it to memory, so the credential round-trip is exercised without an Android device.
 */
internal interface HueStorage {
    fun get(name: String): String?

    /** Stores [value], or removes the entry when it is null; false when the write did not stick. */
    fun put(
        name: String,
        value: String?,
    ): Boolean

    fun clear(): Boolean
}

/** The bridge's own `hue-<id>` SharedPreferences file, removed wholesale on forget. */
@SuppressLint("UseKtx") // commit() results are checked; KTX edit {} would discard them.
internal class HuePrefsStorage(
    context: Context,
    id: String,
) : HueStorage {
    private val appContext = context.applicationContext
    private val fileName = "hue-$id"
    private val prefs = appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun get(name: String): String? = prefs.getString(name, null)

    override fun put(
        name: String,
        value: String?,
    ): Boolean =
        prefs
            .edit()
            .apply { if (value == null) remove(name) else putString(name, value) }
            .commit()

    override fun clear(): Boolean {
        val cleared = prefs.edit().clear().commit()
        appContext.deleteSharedPreferences(fileName)
        return cleared
    }
}

/**
 * Everything kept for one saved bridge: its address, the app key the link-button flow minted, and the
 * SPKI pin its certificate was trusted on.
 *
 * The app key is sealed with an Android Keystore AES-GCM key (`allowBackup=false` keeps it on this
 * phone); the pin is a public-key hash and is stored as text. Credentials are read only through
 * [applicationKey]/[clientKey], and are never written to a log.
 */
internal class HueCredentials(
    private val storage: HueStorage,
    private val key: () -> SecretKey,
) {
    val host: String? get() = storage.get(KEY_HOST)
    val pin: String? get() = storage.get(KEY_PIN)?.takeIf { it.isNotBlank() }
    val applicationKey: String? get() = pairing()?.applicationKey
    val clientKey: String? get() = pairing()?.clientKey

    /** Records the bridge address and the pin trust-on-first-use accepted for it. */
    fun saveTrust(
        host: String,
        pin: String,
    ): Boolean = storage.put(KEY_HOST, host) && storage.put(KEY_PIN, pin)

    /** Records the bridge address and the app key the link-button flow returned, sealed. */
    fun savePairing(
        host: String,
        pairing: HuePairing,
    ): Boolean = storage.put(KEY_HOST, host) && storage.put(KEY_PAIRING, seal(pairing))

    /** Clears the address, app key and pin; the saved-device entry is the caller's. */
    fun forget(): Boolean = storage.clear()

    private fun pairing(): HuePairing? = storage.get(KEY_PAIRING)?.let(::open)?.let(HuePairingCodec::decode)

    private fun seal(pairing: HuePairing): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val plain = HuePairingCodec.encode(pairing).toByteArray(Charsets.UTF_8)
        val sealed =
            try {
                cipher.iv + cipher.doFinal(plain)
            } finally {
                plain.fill(0)
            }
        return Base64.getEncoder().encodeToString(sealed)
    }

    // AES/GCM's 12-byte IV and 128-bit tag describe the stored format.
    @Suppress("MagicNumber")
    private fun open(value: String): String? =
        try {
            val data = Base64.getDecoder().decode(value)
            val cipher =
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, IV_BYTES)))
                }
            String(cipher.doFinal(data.copyOfRange(IV_BYTES, data.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val KEY_HOST = "host"
        private const val KEY_PIN = "pin"
        private const val KEY_PAIRING = "pairing"
        private const val KEY_ALIAS = "hue-pairing"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** The Keystore key that seals the app key; one alias serves every bridge. */
        fun androidKeystoreKey(): SecretKey {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            return store.getKey(KEY_ALIAS, null) as? SecretKey ?: KeyGenerator
                .getInstance("AES", ANDROID_KEYSTORE)
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
    }
}
