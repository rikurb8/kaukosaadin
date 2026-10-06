package fi.goodconsulting.kaukosaadin.ui

import android.annotation.SuppressLint
import android.content.Context
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.AppleTvApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What the Super remote is bound to: one saved Apple TV, one saved Hue Bridge with one of its rooms'
 * or zones' grouped lights, and the two app shortcuts. Everything is kept by id, so a rename on the
 * device or the bridge follows by itself, and it is the Super remote's own configuration rather than
 * the device picker's current selection.
 */
internal data class SuperRemoteBindings(
    val appleTvDeviceId: String? = null,
    val hueDeviceId: String? = null,
    /** The grouped-light id of the chosen room or zone on [hueDeviceId]. */
    val hueGroupedLightId: String? = null,
    /** The chosen room's or zone's name, shown until the bridge reports what the target is called now. */
    val hueGroupedLightName: String? = null,
    /** The app shortcuts in the order they are shown, each with the bundle id it launches. */
    val shortcuts: List<AppleTvApp> = emptyList(),
) {
    /**
     * The sections' configured devices, resolved against the currently saved [devices]. A binding
     * whose saved device was forgotten, or is no longer saved as the kind it was bound as, resolves
     * to null for that section: nothing falls back to another saved device, another bridge or another
     * light, so the section asks for reselection instead of quietly commanding something else.
     */
    fun resolve(devices: List<SavedDevice>): SuperRemoteTargets =
        SuperRemoteTargets(
            appleTv = devices.firstOrNull { it.kind == DeviceKind.AppleTv && it.id == appleTvDeviceId },
            lighting = lighting(devices),
        )

    /** The bound bridge and target, or null when the bridge was forgotten or no grouped light was chosen. */
    private fun lighting(devices: List<SavedDevice>): SuperRemoteLighting? {
        val bridge = devices.firstOrNull { it.kind == DeviceKind.Hue && it.id == hueDeviceId }
        val targetId = hueGroupedLightId?.takeIf { it.isNotBlank() }
        if (bridge == null || targetId == null) return null
        val name = hueGroupedLightName?.takeIf { it.isNotBlank() } ?: targetId
        return SuperRemoteLighting(bridge, SuperRemoteGroupedLight(targetId, name))
    }

    companion object {
        fun encode(bindings: SuperRemoteBindings): String =
            JSONObject()
                .put(APPLE_TV, bindings.appleTvDeviceId)
                .put(HUE_DEVICE, bindings.hueDeviceId)
                .put(HUE_GROUPED_LIGHT, bindings.hueGroupedLightId)
                .put(HUE_GROUPED_LIGHT_NAME, bindings.hueGroupedLightName)
                .put(SHORTCUTS, JSONArray(bindings.shortcuts.map { JSONObject().put(BUNDLE_ID, it.bundleId).put(NAME, it.name) }))
                .toString()

        /** Unreadable storage, unknown or missing values decode to "not configured" rather than crashing. */
        fun decode(json: String?): SuperRemoteBindings {
            val stored =
                try {
                    JSONObject(json ?: "")
                } catch (_: JSONException) {
                    return SuperRemoteBindings()
                }
            return SuperRemoteBindings(
                appleTvDeviceId = stored.id(APPLE_TV),
                hueDeviceId = stored.id(HUE_DEVICE),
                hueGroupedLightId = stored.id(HUE_GROUPED_LIGHT),
                hueGroupedLightName = stored.id(HUE_GROUPED_LIGHT_NAME),
                shortcuts = shortcuts(stored.optJSONArray(SHORTCUTS)),
            )
        }

        /** A shortcut without a bundle id is dropped: there would be no app to ask the TV to open. */
        private fun shortcuts(array: JSONArray?): List<AppleTvApp> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val bundleId = entry.optString(BUNDLE_ID).trim()
                if (bundleId.isEmpty()) return@mapNotNull null
                AppleTvApp(bundleId, entry.optString(NAME).trim().ifEmpty { bundleId })
            }
        }

        private fun JSONObject.id(key: String): String? = optString(key).trim().takeIf { it.isNotEmpty() }

        private const val APPLE_TV = "appleTvDeviceId"
        private const val HUE_DEVICE = "hueDeviceId"
        private const val HUE_GROUPED_LIGHT = "hueGroupedLightId"
        private const val HUE_GROUPED_LIGHT_NAME = "hueGroupedLightName"
        private const val SHORTCUTS = "shortcuts"
        private const val BUNDLE_ID = "bundleId"
        private const val NAME = "name"
    }
}

/** The room or zone's grouped light the lighting section commands, and the name to show for it. */
internal data class SuperRemoteGroupedLight(
    val id: String,
    val name: String,
)

/** The lighting section's bound bridge and the room or zone chosen on it. */
internal data class SuperRemoteLighting(
    val bridge: SavedDevice,
    val target: SuperRemoteGroupedLight,
)

/** What the Super remote's sections are bound to once their bindings are resolved against the saved devices. */
internal data class SuperRemoteTargets(
    /** The bound Apple TV, or null when it was forgotten or is no longer saved as an Apple TV. */
    val appleTv: SavedDevice?,
    /** The bound bridge with its room or zone, or null when the bridge or the target is gone. */
    val lighting: SuperRemoteLighting?,
)

/**
 * The Super remote's bindings in their own `super_remote` storage, apart from the saved devices and
 * the picker's selection: adding, picking or forgetting a device there never rewrites what the Super
 * remote is bound to, and the bindings themselves never change the selection.
 */
@SuppressLint("UseKtx") // commit() results are checked; KTX edit {} would discard them.
internal class SuperRemoteStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("super_remote", Context.MODE_PRIVATE)
    private val mutableBindings = MutableStateFlow(SuperRemoteBindings.decode(prefs.getString(BINDINGS, null)))
    val bindings = mutableBindings.asStateFlow()

    /** Replaces the stored bindings, returning whether they were stored; nothing changes when they were not. */
    fun write(bindings: SuperRemoteBindings): Boolean {
        val stored = prefs.edit().putString(BINDINGS, SuperRemoteBindings.encode(bindings)).commit()
        if (stored) mutableBindings.value = bindings
        return stored
    }

    private companion object {
        const val BINDINGS = "bindings"
    }
}
