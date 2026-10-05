package fi.goodconsulting.kaukosaadin.device

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What a saved device is; each kind has its own client, pairing and key set. */
enum class DeviceKind(
    val label: String,
    val annunciator: String,
    val platform: String,
) {
    Lg("LG TV", "TV", "WEBOS"),
    AppleTv("Apple TV", "ATV", "TVOS"),
}

/**
 * A paired device the remote can drive. [host] is the address it was paired at, kept for display and
 * to recognise it in scans; the client's own storage holds what it connects with and its pairing.
 */
data class SavedDevice(
    val id: String,
    val kind: DeviceKind,
    val name: String,
    val host: String,
)

/** The saved-device list and the device the remote last drove; names live only here. */
@SuppressLint("UseKtx") // commit() results are checked; KTX edit {} would discard them.
class DeviceStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private val mutableDevices = MutableStateFlow(decode(prefs.getString("list", null)))
    val devices = mutableDevices.asStateFlow()
    private val mutableSelectedId = MutableStateFlow(prefs.getString("selected", null))
    val selectedId = mutableSelectedId.asStateFlow()

    init {
        // The single-slot storage from before saved devices; its pairings are not carried over.
        context.applicationContext.deleteSharedPreferences("lg")
        context.applicationContext.deleteSharedPreferences("companion")
    }

    // Each change returns whether it was stored; nothing changes in memory when it was not.

    /** Saves a newly paired device and makes it the one the remote drives. */
    fun add(device: SavedDevice): Boolean {
        val named = device.copy(name = displayName(device.name, device.kind))
        return write(devices.value.filterNot { it.id == device.id } + named, device.id)
    }

    fun rename(
        id: String,
        name: String,
    ) = write(devices.value.map { if (it.id == id) it.copy(name = displayName(name, it.kind)) else it }, selectedId.value)

    fun remove(id: String) = write(devices.value.filterNot { it.id == id }, selectedId.value?.takeUnless { it == id })

    fun select(id: String) = write(devices.value, id)

    private fun write(
        list: List<SavedDevice>,
        selected: String?,
    ): Boolean {
        val stored =
            prefs
                .edit()
                .putString("list", encode(list))
                .putString("selected", selected)
                .commit()
        if (stored) {
            mutableDevices.value = list
            mutableSelectedId.value = selected
        }
        return stored
    }

    companion object {
        private const val MAX_NAME_CHARS = 160

        /** Advertised or typed names, made safe to show; blank falls back to the kind's label. */
        fun displayName(
            value: String,
            kind: DeviceKind,
        ): String =
            value
                .filterNot { it.isISOControl() }
                .trim()
                .take(MAX_NAME_CHARS)
                .ifBlank { kind.label }

        /** The last-used device while it is still saved; otherwise an Apple TV, otherwise the first device. */
        fun current(
            devices: List<SavedDevice>,
            selectedId: String?,
        ): SavedDevice? =
            devices.firstOrNull { it.id == selectedId }
                ?: devices.firstOrNull { it.kind == DeviceKind.AppleTv }
                ?: devices.firstOrNull()

        internal fun encode(devices: List<SavedDevice>): String =
            JSONArray(
                devices.map {
                    JSONObject()
                        .put("id", it.id)
                        .put("kind", it.kind.name)
                        .put("name", it.name)
                        .put("host", it.host)
                },
            ).toString()

        /** Unreadable storage or unknown kinds are dropped rather than crashing the remote. */
        internal fun decode(json: String?): List<SavedDevice> {
            val array =
                try {
                    JSONArray(json ?: "[]")
                } catch (_: JSONException) {
                    JSONArray()
                }
            return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::device) }
        }

        private fun device(entry: JSONObject): SavedDevice? {
            val kind = DeviceKind.entries.firstOrNull { it.name == entry.optString("kind") }
            val id = entry.optString("id")
            if (kind == null || id.isEmpty()) return null
            return SavedDevice(id, kind, displayName(entry.optString("name"), kind), entry.optString("host"))
        }
    }
}
