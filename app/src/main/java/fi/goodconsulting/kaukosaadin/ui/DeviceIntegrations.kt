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
 * the Add device section order.
 */
internal object DeviceIntegrations {
    val all: List<DeviceIntegration> = listOf(AppleTvIntegration, LgIntegration, HueIntegration)

    /** The integration for [kind]; every [DeviceKind] has exactly one (see DeviceIntegrationsTest). */
    fun of(kind: DeviceKind): DeviceIntegration = all.first { it.kind == kind }
}

/**
 * One device kind's own setup, main screen and settings extras. The shell owns navigation,
 * theme/layout preferences and the common rename/forget settings, and routes to the selected
 * device's integration by [kind], so a kind owns its client and controls end to end.
 */
internal interface DeviceIntegration {
    val kind: DeviceKind

    /**
     * The kind's part of the Add device screen: its scan, results and pairing. It scans on first
     * composition and whenever [SetupHost.scanToken] changes, so the screen's one Scan button
     * searches every registered kind.
     */
    @Composable
    fun Setup(host: SetupHost)

    /** The live controls for [device]; the shell keeps them while that device stays selected. */
    fun controls(
        context: Context,
        device: SavedDevice,
    ): DeviceControls
}

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

    /** The kind's extra rows on Device settings, below the common name and forget controls. */
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
    val onSettings: () -> Unit,
    val onGeneralSettings: () -> Unit,
)

/** The Add device screen's shared state, handed to every integration's [DeviceIntegration.Setup]. */
internal class SetupHost(
    val store: DeviceStore,
    val scanToken: Int,
    val busy: Boolean,
    val message: String,
    val onMessage: (String) -> Unit,
    val run: (suspend () -> Unit) -> Unit,
    val onScanning: (Boolean) -> Unit,
    val onAdded: () -> Unit,
)
