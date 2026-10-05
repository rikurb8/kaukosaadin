package fi.goodconsulting.kaukosaadin.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lgtvremote.discovery.TVDiscovery
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.LgClient
import fi.goodconsulting.kaukosaadin.device.LgProtocol
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.scanLg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Registers the LG TV kind: SSDP scan and trust/PIN pairing, the remote, and re-pair/wake extras. */
internal object LgIntegration : DeviceIntegration {
    override val kind = DeviceKind.Lg

    @Composable
    override fun Setup(host: SetupHost) = LgSetup(host)

    override fun controls(
        context: Context,
        device: SavedDevice,
    ): DeviceControls = LgControls(LgClient(context, device.id))
}

/** One saved LG TV's live client and screens; the certificate pin and pairing live in [LgClient]. */
private class LgControls(
    private val client: LgClient,
) : DeviceControls {
    override val forgetDetail =
        "This forgets the TV, its pairing and wake settings. You'll need to scan and pair it again."

    @Composable
    override fun Remote(
        padding: PaddingValues,
        remote: RemoteActions,
    ) {
        LgPinDialog(client)
        LgRemote(padding, remote, client)
    }

    @Composable
    override fun Settings(
        device: SavedDevice,
        busy: Boolean,
        run: (suspend () -> Unit) -> Unit,
    ) {
        LgPinDialog(client)
        LgSettings(client, device, busy, run)
    }

    override suspend fun forget(): String? = client.forget().takeUnless { it.ok }?.message
}

/** An LG TV whose certificate was inspected and now awaits the user's trust decision. */
private class LgCandidate(
    val client: LgClient,
    val id: String,
    val host: String,
    val fingerprint: String,
)

/** LG's part of Add device: SSDP results and the manual address fallback, then trust/PIN pairing. */
@Suppress("CyclomaticComplexMethod") // Scan, certificate inspection and pairing share one state.
@Composable
private fun LgSetup(host: SetupHost) {
    val context = LocalContext.current.applicationContext
    val saved by host.store.devices.collectAsState()
    var found by remember { mutableStateOf(emptyList<TVDiscovery.DiscoveredTV>()) }
    var scan by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<LgCandidate?>(null) }
    var name by remember { mutableStateOf("") }
    var manualAddress by remember { mutableStateOf("") }

    LaunchedEffect(host.scanToken) {
        host.onScanning(true)
        scan = "Searching for LG TVs…"
        try {
            found = scanLg(context)
            scan = if (found.isEmpty()) "No LG TVs replied. Turn the TV on, or enter its address below." else ""
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            scan = "LG scan failed. Check Wi-Fi/LAN access, then scan again."
        } finally {
            host.onScanning(false)
        }
    }

    fun isSaved(address: String) = saved.any { it.kind == DeviceKind.Lg && it.host == address }

    fun inspect(
        address: String,
        advertised: String,
    ) {
        host.run {
            val id = UUID.randomUUID().toString()
            val client = LgClient(context, id)
            host.onMessage("Reading the certificate of $address…")
            val fingerprint = client.inspect(address)
            if (fingerprint == null) {
                host.onMessage(client.status.value.message)
                client.forget()
            } else {
                host.onMessage("")
                name = advertised
                candidate = LgCandidate(client, id, address, fingerprint)
            }
        }
    }

    fun cancel() {
        val pending = candidate ?: return
        candidate = null
        host.run { pending.client.forget() }
    }

    fun pair(pending: LgCandidate) {
        host.run {
            var added = false
            try {
                val result = pending.client.pair(pending.host, pending.fingerprint)
                host.onMessage(result.message)
                added = result.ok && host.store.add(SavedDevice(pending.id, DeviceKind.Lg, name, pending.host))
                if (result.ok && !added) host.onMessage(SAVE_FAILED)
            } finally {
                candidate = null
                if (!added) withContext(NonCancellable) { pending.client.forget() }
            }
            if (added) host.onAdded()
        }
    }

    candidate?.let { pending ->
        LgPinDialog(pending.client)
        // Hidden while pairing runs, so the TV's PIN dialog is the only one on screen.
        if (!host.busy) {
            LgTrustDialog(
                name = name,
                host = pending.host,
                fingerprint = pending.fingerprint,
                onName = { name = it },
                onTrust = { pair(pending) },
                onCancel = ::cancel,
            )
        }
    }
    Text("LG TV", style = MaterialTheme.typography.titleMedium)
    found.forEach { tv ->
        val deviceName = if (tv.name == tv.ip) DeviceKind.Lg.label else tv.name
        FoundDevice(deviceName, tv.ip, isSaved(tv.ip), enabled = !host.busy) { inspect(tv.ip, deviceName) }
    }
    if (scan.isNotEmpty()) Text(scan, style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            manualAddress,
            { manualAddress = it.trim() },
            label = { Text("Enter LG TV address") },
            enabled = !host.busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = !host.busy && manualAddress.isNotEmpty(), onClick = {
            inspect(manualAddress, DeviceKind.Lg.label)
        }) { Text("Add") }
    }
}

/** LG verifies registration with Connect and opens a fresh, pinned TLS session per press. */
@Composable
private fun LgRemote(
    padding: PaddingValues,
    remote: RemoteActions,
    client: LgClient,
) {
    val scope = rememberCoroutineScope()
    val status by client.status.collectAsState()
    val ready by client.ready.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LgClient.Result?>(null) }

    fun run(block: suspend () -> LgClient.Result) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                result = block()
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
        ready = ready,
        busy = busy,
        powerEnabled = client.mac.isNotEmpty() && client.broadcast.isNotEmpty() && client.fingerprint.isNotEmpty(),
        status = (if (busy) status else result ?: status).message,
        onSelect = remote.onSelect,
        onAddDevice = remote.onAddDevice,
        onConnect = { if (client.fingerprint.isEmpty()) remote.onSettings() else run { client.connect() } },
        onApps = null,
        onSettings = remote.onSettings,
        onGeneralSettings = remote.onGeneralSettings,
        onPower = { run { client.send(LgProtocol.Action.Wake) } },
        onKey = { key, _ -> key.lg?.let { action -> run { client.send(action) } } },
    )
}

/** Re-pair (trust the certificate again and enter a new PIN) and the optional Wake-on-LAN settings. */
@Composable
private fun LgSettings(
    client: LgClient,
    device: SavedDevice,
    busy: Boolean,
    run: (suspend () -> Unit) -> Unit,
) {
    val status by client.status.collectAsState()
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var mac by remember { mutableStateOf(client.mac) }
    var broadcast by remember { mutableStateOf(client.broadcast) }
    val wakeSaved = mac.isNotEmpty() && broadcast.isNotEmpty() && mac == client.mac && broadcast == client.broadcast

    fingerprint?.let { inspected ->
        LgTrustDialog(
            name = device.name,
            host = device.host,
            fingerprint = inspected,
            onName = null,
            onTrust = {
                fingerprint = null
                run { client.pair(device.host, inspected) }
            },
            onCancel = { fingerprint = null },
        )
    }
    Text("Pairing", style = MaterialTheme.typography.titleMedium)
    Text(status.message)
    Button(enabled = !busy, onClick = { run { fingerprint = client.inspect(device.host) } }) { Text("Re-pair") }
    Text(
        "Re-pair reads the TV's certificate again; pairing finishes with the PIN the TV shows.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Wake (optional)", style = MaterialTheme.typography.titleMedium)
    Text("Discovery does not provide a wake MAC. Enter the TV's active network MAC and subnet broadcast only for wake.")
    OutlinedTextField(mac, { mac = it }, label = { Text("TV network MAC") }, enabled = !busy)
    OutlinedTextField(broadcast, { broadcast = it }, label = { Text("Subnet broadcast IPv4") }, enabled = !busy)
    Button(enabled = !busy, onClick = { run { client.saveWake(device.host, mac, broadcast) } }) { Text("Save wake settings") }
    Button(enabled = !busy && wakeSaved, onClick = { run { client.send(LgProtocol.Action.Wake) } }) { Text("Wake TV") }
}
