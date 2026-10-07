package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceStore
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import kotlinx.coroutines.launch

/**
 * Every saved device on one screen: which one the remote drives, each one's settings, and forget.
 * Forgetting goes through the same [DeviceStore.forget] path as Device settings, with the device's
 * own kind clearing what it keeps.
 */
@Composable
internal fun DevicesScreen(
    padding: PaddingValues,
    store: DeviceStore,
    devices: List<SavedDevice>,
    current: SavedDevice?,
    onSelect: (SavedDevice) -> Unit,
    onOpen: (SavedDevice) -> Unit,
    onAddDevice: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var forgetting by remember { mutableStateOf<SavedDevice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    forgetting?.let { device ->
        ConfirmForget(device, onDismiss = { forgetting = null }) { controls ->
            forgetting = null
            busy = true
            scope.launch {
                try {
                    message = store.forget(device.id) { controls.forget() }.orEmpty()
                } finally {
                    busy = false
                }
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Your devices", style = MaterialTheme.typography.headlineMedium)
        if (devices.isEmpty()) {
            Text(
                "No devices yet. Add a TV, Apple TV or Hue Bridge to start using the remote.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        devices.forEach { device ->
            val inUse = device.id == current?.id
            DeviceCard(
                kind = device.kind,
                title = device.name,
                subtitle = device.kind.label,
                onClick = { onOpen(device) },
                tag = if (inUse) "In use" else null,
                trailing = {
                    DeviceMenu(
                        device = device,
                        inUse = inUse,
                        enabled = !busy,
                        onSelect = { onSelect(device) },
                        onOpen = { onOpen(device) },
                        onForget = { forgetting = device },
                    )
                },
            )
        }
        Button(onClick = onAddDevice, modifier = Modifier.fillMaxWidth()) { Text("Add a device") }
    }
}

/** Asks before forgetting [device], with what its kind also clears; [onConfirm] gets its controls to forget through. */
@Composable
private fun ConfirmForget(
    device: SavedDevice,
    onDismiss: () -> Unit,
    onConfirm: (DeviceControls) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val controls = remember(device.id) { DeviceIntegrations.of(device.kind).controls(context, device) }
    ForgetDeviceDialog(device = device, detail = controls.forgetDetail, onDismiss = onDismiss, onConfirm = { onConfirm(controls) })
}

@Composable
private fun DeviceMenu(
    device: SavedDevice,
    inUse: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onForget: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "More options for ${device.name}" },
        ) { MoreGlyph(MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!inUse) {
                DropdownMenuItem(text = { Text("Use this device") }, onClick = {
                    open = false
                    onSelect()
                })
            }
            DropdownMenuItem(text = { Text("Settings") }, onClick = {
                open = false
                onOpen()
            })
            DropdownMenuItem(
                text = { Text("Forget…", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onForget()
                },
            )
        }
    }
}
