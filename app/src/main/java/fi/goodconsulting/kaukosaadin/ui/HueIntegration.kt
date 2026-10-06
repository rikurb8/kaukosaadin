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
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.hue.HueClient
import fi.goodconsulting.kaukosaadin.device.hue.HueDiscovery
import fi.goodconsulting.kaukosaadin.device.hue.HueFavorites
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import fi.goodconsulting.kaukosaadin.device.hue.HueProtocol
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** Registers the Hue Bridge kind: mDNS discovery and link-button pairing, and the bridge's lighting screen. */
internal object HueIntegration : DeviceIntegration {
    override val kind = DeviceKind.Hue

    @Composable
    override fun Setup(host: SetupHost) = HueSetup(host)

    override fun controls(
        context: Context,
        device: SavedDevice,
    ): DeviceControls = HueControls(HueClient(context, device.id))
}

/** One saved bridge's stored credentials and trust, and the lighting screen that drives its lights. */
private class HueControls(
    private val client: HueClient,
) : DeviceControls {
    override val forgetDetail =
        "This forgets the bridge, its stored app key and its certificate pin. You'll need to pair it again."

    @Composable
    override fun Remote(
        padding: PaddingValues,
        remote: RemoteActions,
    ) {
        val scope = rememberCoroutineScope()
        val favorites = remember(client) { HueFavorites(client.storage) }
        val lighting = remember(client) { HueLighting.of(client, scope) }
        LightingScreen(padding, remote, lighting, favorites)
    }

    @Composable
    override fun Settings(
        device: SavedDevice,
        busy: Boolean,
        run: (suspend () -> Unit) -> Unit,
    ) {
        val status by client.status.collectAsState()
        Text("Pairing", style = MaterialTheme.typography.titleMedium)
        Text(status.message)
        Text("Bridge address: ${client.host ?: device.host}")
        Text(
            if (client.pin != null) {
                "Certificate: pinned to the paired bridge"
            } else {
                "Certificate: verified by the system CA store, or not connected yet"
            },
        )
        Text("To pair again, forget this bridge and add it from the scan.", style = MaterialTheme.typography.bodySmall)
    }

    override suspend fun forget(): String? = client.forget().takeUnless { it.ok }?.message
}

/** A bridge the operator picked and is pairing; its client owns the app key and pin under its own id. */
private class HueCandidate(
    val client: HueClient,
    val id: String,
    val host: String,
    val name: String,
)

/** Hue's part of Add device: mDNS results and the manual address, then link-button pairing. */
@Suppress("CyclomaticComplexMethod") // Scan, manual entry and link-button pairing share one state.
@Composable
private fun HueSetup(host: SetupHost) {
    val context = LocalContext.current.applicationContext
    val discovery = remember(context) { HueDiscovery(context) }
    val saved by host.store.devices.collectAsState()
    var found by remember { mutableStateOf(emptyList<HueDiscovery.Bridge>()) }
    var scan by remember { mutableStateOf("") }
    var manualAddress by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<HueCandidate?>(null) }

    LaunchedEffect(host.scanToken) {
        host.onScanning(true)
        scan = "Searching for Hue Bridges…"
        try {
            found = discovery.scan()
            scan = if (found.isEmpty()) "No Hue Bridges replied. Turn the bridge on, or enter its address below." else ""
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            scan = "Hue scan failed. Check Wi-Fi/LAN access, then scan again."
        } finally {
            host.onScanning(false)
        }
    }

    fun isSaved(address: String) = saved.any { it.kind == DeviceKind.Hue && it.host == address }

    fun pair() {
        val pending = candidate ?: return
        host.run {
            val result = pending.client.pair(pending.host)
            host.onMessage(result.message)
            when {
                result.ok && host.store.add(SavedDevice(pending.id, DeviceKind.Hue, pending.name, pending.host)) -> {
                    candidate = null
                    host.onAdded()
                }
                result.ok -> host.onMessage(SAVE_FAILED)
                // Error 101 and other failures leave the candidate in place, so the operator presses
                // the link button and taps "Pair again"; nothing retries on its own.
                else -> Unit
            }
        }
    }

    fun start(
        address: String,
        name: String,
    ) {
        if (candidate != null) return
        val id = UUID.randomUUID().toString()
        candidate = HueCandidate(HueClient(context, id), id, address, name)
        pair()
    }

    fun cancel() {
        val pending = candidate ?: return
        candidate = null
        host.run { pending.client.forget() }
    }

    Text("Hue Bridge", style = MaterialTheme.typography.titleMedium)
    found.forEach { bridge ->
        val address = bridge.address.hostAddress.orEmpty()
        val label = bridge.model?.let { "${bridge.name} · $it" } ?: bridge.name
        FoundDevice(label, address, isSaved(address), enabled = !host.busy && candidate == null) { start(address, bridge.name) }
    }
    if (scan.isNotEmpty()) Text(scan, style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            manualAddress,
            { manualAddress = it.trim() },
            label = { Text("Enter Hue Bridge address") },
            enabled = !host.busy && candidate == null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = !host.busy && candidate == null && manualAddress.isNotEmpty(), onClick = {
            val address = runCatching { HueProtocol.ipv4(manualAddress) }.getOrNull()
            if (address == null) {
                host.onMessage(HueProtocol.INVALID_ADDRESS)
            } else {
                start(address, DeviceKind.Hue.label)
            }
        }) { Text("Add") }
    }
    candidate?.let { pending ->
        Text("Pairing ${pending.host}", style = MaterialTheme.typography.titleMedium)
        Text("Press the link button on the bridge, then tap Pair again.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !host.busy, onClick = { pair() }) { Text("Pair again") }
            TextButton(enabled = !host.busy, onClick = { cancel() }) { Text("Cancel") }
        }
    }
}
