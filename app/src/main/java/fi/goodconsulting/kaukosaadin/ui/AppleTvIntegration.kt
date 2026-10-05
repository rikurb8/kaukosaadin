package fi.goodconsulting.kaukosaadin.ui

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.companion.CompanionDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Registers the Apple TV kind: Companion mDNS scan and PIN pairing, the remote and its apps list. */
internal object AppleTvIntegration : DeviceIntegration {
    override val kind = DeviceKind.AppleTv

    @Composable
    override fun Setup(host: SetupHost) = AppleTvSetup(host)

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
        val activity = LocalActivity.current
        var connecting by remember { mutableStateOf(false) }
        // Opening the remote connects once and holds the session while it is visible, apps list
        // included; leaving or backgrounding closes it. A new session restarts the effect.
        LaunchedEffect(activity, client) {
            if (activity is LifecycleOwner) {
                activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    try {
                        connecting = true
                        try {
                            client.connect()
                        } finally {
                            connecting = false
                        }
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { client.disconnect() }
                    }
                }
            }
        }
        AppleTvKeyboardDialog(client)
        var showApps by rememberSaveable(remote.current.id) { mutableStateOf(false) }
        if (showApps) {
            AppleTvAppsScreen(padding, client, onBack = { showApps = false })
        } else {
            AppleTvRemote(padding, remote, client, connecting, onApps = { showApps = true })
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

/** Apple TV's part of Add device: Companion mDNS results, then PIN pairing and saving. */
@Suppress("CyclomaticComplexMethod") // Scan and pairing share one state.
@Composable
private fun AppleTvSetup(host: SetupHost) {
    val context = LocalContext.current.applicationContext
    val discovery = remember { CompanionDiscovery(context) }
    val saved by host.store.devices.collectAsState()
    var found by remember { mutableStateOf(emptyList<CompanionDiscovery.Device>()) }
    var scan by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf<CompanionClient?>(null) }

    LaunchedEffect(host.scanToken) {
        host.onScanning(true)
        scan = "Searching for Apple TVs…"
        try {
            found = discovery.scan()
            scan = if (found.isEmpty()) "No Apple TVs found. Wake it with its own remote, then scan again." else ""
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            scan = "Apple TV scan failed. Check Wi-Fi/LAN access, then scan again."
        } finally {
            host.onScanning(false)
        }
    }

    fun isSaved(address: String) = saved.any { it.kind == DeviceKind.AppleTv && it.host == address }

    fun pair(device: CompanionDiscovery.Device) {
        host.run {
            val id = UUID.randomUUID().toString()
            val client = CompanionClient(context, id)
            val address = device.address.hostAddress.orEmpty()
            var added = false
            pairing = client
            host.onMessage("Pairing with ${device.name}…")
            try {
                val result = client.pair(device)
                host.onMessage(result.message)
                added = result.ok && host.store.add(SavedDevice(id, DeviceKind.AppleTv, device.name, address))
                if (result.ok && !added) host.onMessage(SAVE_FAILED)
            } finally {
                pairing = null
                if (!added) withContext(NonCancellable) { client.forget() }
            }
            if (added) host.onAdded()
        }
    }

    pairing?.let { AppleTvPinDialog(it) }
    Text("Apple TV", style = MaterialTheme.typography.titleMedium)
    found.forEach { device ->
        val address = device.address.hostAddress.orEmpty()
        FoundDevice(device.name, address, isSaved(address), enabled = !host.busy) { pair(device) }
    }
    if (scan.isNotEmpty()) Text(scan, style = MaterialTheme.typography.bodySmall)
}

/** The Apple TV session is opened by [AppleTvControls] while this is visible; presses reuse it. */
@Composable
private fun AppleTvRemote(
    padding: PaddingValues,
    remote: RemoteActions,
    client: CompanionClient,
    connecting: Boolean,
    onApps: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status by client.status.collectAsState()
    val paired by client.paired.collectAsState()
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }
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
        onConnect = null,
        onApps = onApps,
        onSettings = remote.onSettings,
        onGeneralSettings = remote.onGeneralSettings,
        onPower = { run { client.sleep() } },
        onKey = { key, action -> run { client.press(key.hid, action) } },
    )
}

/** Apple TV's extra settings rows: pairing status and how to pair it again. */
@Composable
private fun AppleTvSettings(client: CompanionClient) {
    val status by client.status.collectAsState()
    Text("Pairing", style = MaterialTheme.typography.titleMedium)
    Text(status.message)
    Text(
        "To pair again, forget this Apple TV and add it from the scan. There is no Apple TV wake.",
        style = MaterialTheme.typography.bodySmall,
    )
}
