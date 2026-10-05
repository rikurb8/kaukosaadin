package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.DeviceStore
import fi.goodconsulting.kaukosaadin.device.LgClient
import fi.goodconsulting.kaukosaadin.device.LgProtocol
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import kotlinx.coroutines.launch

/**
 * Settings for one saved device: its name and removal for every kind, plus re-pairing and wake for
 * LG. Exactly one of [lg] / [apple] is the device's client, matching its kind.
 */
@Composable
fun DeviceSettingsScreen(
    padding: PaddingValues,
    device: SavedDevice,
    store: DeviceStore,
    lg: LgClient?,
    apple: CompanionClient?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var name by remember(device.id) { mutableStateOf(device.name) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }

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
    if (confirmRemove) {
        RemoveDeviceDialog(
            device = device,
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                run {
                    val failure =
                        lg?.delete()?.takeUnless { it.ok }?.message
                            ?: apple?.delete()?.takeUnless { it.ok }?.message
                    if (failure != null) {
                        message = failure
                    } else if (store.remove(device.id)) {
                        onBack()
                    } else {
                        message = "Could not remove the device. Try again."
                    }
                }
            },
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Device settings", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("Done") }
        Text("${device.kind.label} · ${device.host}")
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, enabled = !busy, singleLine = true)
        OutlinedButton(enabled = !busy && name != device.name, onClick = {
            message = if (store.rename(device.id, name)) "Name saved." else "Could not save the name. Try again."
        }) { Text("Save name") }
        if (message.isNotEmpty()) Text(message)
        if (lg != null) LgSettings(lg, device, busy, ::run)
        if (apple != null) AppleTvSettings(apple)
        TextButton(enabled = !busy, onClick = { confirmRemove = true }) { Text("Remove device") }
        TextButton(onClick = onBack) { Text("Done") }
    }
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

@Composable
private fun AppleTvSettings(client: CompanionClient) {
    val status by client.status.collectAsState()
    Text("Pairing", style = MaterialTheme.typography.titleMedium)
    Text(status.message)
    Text(
        "To pair again, remove this Apple TV and add it from the scan. There is no Apple TV wake.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun RemoveDeviceDialog(
    device: SavedDevice,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${device.name}?") },
        text = {
            Text(
                if (device.kind == DeviceKind.AppleTv) {
                    "This removes the Apple TV and its pairing from this phone. Also remove " +
                        "\"${CompanionClient.DISPLAY_NAME}\" in Apple TV Settings › Remotes and Devices."
                } else {
                    "This removes the TV, its pairing and wake settings. You'll need to scan and pair it again."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
