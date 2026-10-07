package fi.goodconsulting.kaukosaadin.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.companion.CompanionDiscovery
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/** Registers the Apple TV kind: Companion mDNS scan and PIN pairing, the remote and its apps list. */
internal object AppleTvIntegration : DeviceIntegration {
    override val kind = DeviceKind.AppleTv

    override suspend fun scan(
        context: Context,
        onFound: (List<Candidate>) -> Unit,
    ): List<Candidate> {
        val devices = CompanionDiscovery(context).scan { onFound(it.map(::AppleTvCandidate)) }
        return devices.map(::AppleTvCandidate)
    }

    override fun controls(
        context: Context,
        device: SavedDevice,
    ): DeviceControls = AppleTvControls(CompanionClient(context, device.id))
}

/** One saved Apple TV's live client and screens; the verified session lives while the remote is shown. */
private class AppleTvControls(
    private val client: CompanionClient,
) : DeviceControls {
    override val forgetDetail =
        "This forgets the Apple TV and its pairing on this phone. Also remove " +
            "\"${CompanionClient.DISPLAY_NAME}\" in Apple TV Settings › Remotes and Devices."

    @Composable
    override fun Remote(
        padding: PaddingValues,
        remote: RemoteActions,
    ) {
        // The session seam owns the RESUMED-scoped connect/disconnect, the keyboard dialog and the
        // single-in-flight dispatch; this screen only renders it. A new session restarts the effect.
        AppleTvSessionHost(client) { session ->
            var showApps by rememberSaveable(remote.current.id) { mutableStateOf(false) }
            if (showApps) {
                AppleTvAppsScreen(padding, client, onBack = { showApps = false })
            } else {
                AppleTvRemote(padding, remote, client, session, onApps = { showApps = true })
            }
        }
    }

    @Composable
    override fun Settings(
        device: SavedDevice,
        busy: Boolean,
        run: (suspend () -> Unit) -> Unit,
    ) {
        AppleTvSettings(client)
    }

    override suspend fun forget(): String? = client.forget().takeUnless { it.ok }?.message
}

/** An Apple TV a scan returned; Apple TVs are only added from the scan, never by address. */
private class AppleTvCandidate(
    val device: CompanionDiscovery.Device,
) : Candidate {
    override val kind = DeviceKind.AppleTv
    override val name = device.name
    override val host = device.address.hostAddress.orEmpty()
    override val detail = null

    @Composable
    override fun Pairing(host: PairingHost) = AppleTvPairing(this, host)
}

/**
 * Companion pairing as one sheet: connecting, then the four-digit PIN the Apple TV shows, sent as
 * soon as the fourth digit is typed. Each attempt gets a fresh client id; anything it kept is cleared
 * unless the Apple TV ends up saved.
 */
@Composable
private fun AppleTvPairing(
    candidate: AppleTvCandidate,
    host: PairingHost,
) {
    val context = LocalContext.current.applicationContext
    var attempt by remember { mutableIntStateOf(0) }
    val id = remember(attempt) { UUID.randomUUID().toString() }
    val client = remember(id) { CompanionClient(context, id) }
    val awaitingPin by client.awaitingPin.collectAsState()
    var failure by remember(attempt) { mutableStateOf<String?>(null) }
    var pin by remember(attempt) { mutableStateOf("") }
    var pinError by remember(attempt) { mutableStateOf<String?>(null) }
    var submitted by remember(attempt) { mutableStateOf(false) }

    DisposableEffect(client) { onDispose { client.cancelPairing() } }
    LaunchedEffect(client) {
        var added = false
        try {
            val result = client.pair(candidate.device)
            added = result.ok && host.store.add(SavedDevice(id, DeviceKind.AppleTv, candidate.name, candidate.host))
            if (!added) failure = if (result.ok) SAVE_FAILED else result.message
        } finally {
            if (!added) withContext(NonCancellable) { client.forget() }
        }
        if (added) host.onAdded()
    }

    fun submit() {
        val result = client.submitPin(pin)
        submitted = result.ok
        pinError = result.message.takeUnless { result.ok }
        if (!result.ok) pin = ""
    }

    val cancel = StepAction("Cancel", onClick = host.onCancel)
    when {
        failure != null ->
            PairingStep(
                DeviceKind.AppleTv,
                "Couldn't add ${candidate.name}",
                message = failure,
                primary = StepAction("Try again") { attempt++ },
                secondary = cancel,
            )
        awaitingPin ->
            AppleTvPinStep(candidate.name, pin, pinError, cancel, onPin = {
                pin = it
                if (it.length == APPLE_TV_PIN_LENGTH) submit()
            }, onSubmit = ::submit)
        else ->
            PairingStep(
                DeviceKind.AppleTv,
                if (submitted) "Checking the PIN…" else "Connecting to ${candidate.name}…",
                message = if (submitted) null else "A PIN will appear on your TV in a moment.",
                busy = true,
                secondary = cancel,
            )
    }
}

/** The PIN prompt, without starting a pairing connection. */
@Composable
internal fun AppleTvPinStep(
    name: String,
    pin: String,
    error: String?,
    cancel: StepAction,
    onPin: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    PairingStep(
        DeviceKind.AppleTv,
        "Enter the PIN",
        message = "Type the 4-digit PIN shown on $name.",
        error = error,
        secondary = cancel,
    ) {
        PinField(pin, onValue = onPin, maxLength = APPLE_TV_PIN_LENGTH, onDone = onSubmit)
    }
}

private const val APPLE_TV_PIN_LENGTH = 4

/** The Apple TV session is opened by [AppleTvSessionHost] while this is visible; presses reuse it. */
@Composable
private fun AppleTvRemote(
    padding: PaddingValues,
    remote: RemoteActions,
    client: CompanionClient,
    session: AppleTvSession,
    onApps: () -> Unit,
) {
    val status by session.status.collectAsState()
    val paired by session.paired.collectAsState()
    val busy by session.busy.collectAsState()
    val connecting by session.connecting.collectAsState()
    RemoteScreen(
        contentPadding = padding,
        devices = remote.devices,
        current = remote.current,
        layout = remote.layout,
        ready = paired,
        busy = busy || connecting,
        powerEnabled = paired,
        status = status.message,
        onSelect = remote.onSelect,
        onAddDevice = remote.onAddDevice,
        onDevices = remote.onDevices,
        onConnect = null,
        onApps = onApps,
        onSettings = remote.onSettings,
        onGeneralSettings = remote.onGeneralSettings,
        onPower = { session.run { client.sleep() } },
        onKey = { key, action -> session.run { client.press(key.hid, action) } },
    )
}

/** Apple TV's extra settings rows: pairing status and how to pair it again. */
@Composable
private fun AppleTvSettings(client: CompanionClient) {
    val status by client.status.collectAsState()
    Text(status.message)
    Text(
        "To pair again, forget this Apple TV and add it again.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
