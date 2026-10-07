package fi.goodconsulting.kaukosaadin.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.DeviceStore
import fi.goodconsulting.kaukosaadin.device.SavedDevice

/**
 * Every device kind's integration, explicitly registered. Adding a kind is a new file implementing
 * [DeviceIntegration] plus one entry here — no edit to an existing kind's controls. [all] order is
 * the order kinds are listed in on Add a device.
 */
internal object DeviceIntegrations {
    val all: List<DeviceIntegration> = listOf(AppleTvIntegration, LgIntegration, HueIntegration)

    /** The integration for [kind]; every [DeviceKind] has exactly one (see DeviceIntegrationsTest). */
    fun of(kind: DeviceKind): DeviceIntegration = all.first { it.kind == kind }
}

/**
 * One device kind's own discovery, pairing, main screen and settings extras. The shell owns
 * navigation, the one merged scan list, theme/layout preferences and the common rename/forget
 * settings, and routes to a device's integration by [kind], so a kind owns its client and controls
 * end to end.
 */
internal interface DeviceIntegration {
    val kind: DeviceKind

    /**
     * One bounded scan for this kind's devices on the LAN; throws when the scan itself fails. Add a
     * device runs every registered kind's scan side by side and lists the results together.
     */
    suspend fun scan(context: Context): List<Candidate>

    /** Whether the operator can add this kind by typing its address when the scan misses it. */
    val addsByAddress: Boolean get() = false

    /** A candidate at a typed [address]; null when it is not a usable address for this kind. */
    fun candidateAt(address: String): Candidate? = null

    /** The live controls for [device]; the shell keeps them while that device stays selected. */
    fun controls(
        context: Context,
        device: SavedDevice,
    ): DeviceControls
}

/**
 * A device a scan returned, or the operator typed the address of, that is not saved yet. Picking it
 * shows its kind's [Pairing] steps in a sheet over Add a device.
 */
internal interface Candidate {
    val kind: DeviceKind
    val name: String
    val host: String

    /** Extra advertised detail worth showing, such as a bridge's model; null when there is none. */
    val detail: String?

    /**
     * The kind's pairing steps, from connecting to saving. Ends with [PairingHost.onAdded] once the
     * device is paired and saved; leaving composition cancels pairing and clears what it kept.
     */
    @Composable
    fun Pairing(host: PairingHost)
}

/** What a [Candidate]'s pairing steps report to the Add a device screen. */
internal class PairingHost(
    val store: DeviceStore,
    val onAdded: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * Everything an integration does for one saved device. The shell shows [Remote] as the remote,
 * [Settings] as the device's extra settings rows, and calls [forget] from the one forget path.
 */
internal interface DeviceControls {
    /**
     * The kind's own screen for this device: the keypad for a TV, the lighting screen for a bridge,
     * plus any dialogs and sub-screens it opens.
     */
    @Composable
    fun Remote(
        padding: PaddingValues,
        remote: RemoteActions,
    )

    /** The kind's extra rows on Device settings, below the common name and above forget. */
    @Composable
    fun Settings(
        device: SavedDevice,
        busy: Boolean,
        run: (suspend () -> Unit) -> Unit,
    )

    /** What forgetting this device also clears from the phone, shown in the confirmation dialog. */
    val forgetDetail: String

    /** Clears everything the kind keeps locally for the device; null when cleared, else the failure. */
    suspend fun forget(): String?
}

/** What every remote shares, whichever kind of device it drives. */
internal class RemoteActions(
    val devices: List<SavedDevice>,
    val current: SavedDevice,
    val layout: AppLayout,
    val onSelect: (SavedDevice) -> Unit,
    val onAddDevice: () -> Unit,
    val onDevices: () -> Unit,
    val onSettings: () -> Unit,
    val onGeneralSettings: () -> Unit,
)
