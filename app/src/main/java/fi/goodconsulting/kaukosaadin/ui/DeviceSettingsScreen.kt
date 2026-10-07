package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceStore
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import kotlinx.coroutines.launch

/**
 * Settings for one saved device: the common rename and forget controls the shell owns, plus the
 * extras [controls] contributes for the device's kind under Connection.
 */
@Composable
internal fun DeviceSettingsScreen(
    padding: PaddingValues,
    device: SavedDevice,
    store: DeviceStore,
    controls: DeviceControls,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var name by remember(device.id) { mutableStateOf(device.name) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmForget by remember { mutableStateOf(false) }

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
    if (confirmForget) {
        ForgetDeviceDialog(
            device = device,
            detail = controls.forgetDetail,
            onDismiss = { confirmForget = false },
            onConfirm = {
                confirmForget = false
                run {
                    // Forget clears everything kept for the device: the kind's local state
                    // through its controls, then the saved entry. A failure leaves it saved.
                    val failure = store.forget(device.id) { controls.forget() }
                    if (failure != null) {
                        message = failure
                    } else {
                        onBack()
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        DeviceHeader(device)
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        SettingsSection("Name") {
            OutlinedTextField(
                name,
                { name = it },
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(enabled = !busy && name != device.name, onClick = {
                message = if (store.rename(device.id, name)) "" else "Could not save the name. Try again."
            }) { Text("Save name") }
        }
        SettingsSection("Connection") { controls.Settings(device, busy, ::run) }
        OutlinedButton(
            enabled = !busy,
            onClick = { confirmForget = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Forget this device") }
    }
}

/** The device's glyph, name and kind, at the top of its settings. */
@Composable
private fun DeviceHeader(device: SavedDevice) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        KindGlyph(device.kind, size = 56.dp)
        Column {
            Text(device.name, style = MaterialTheme.typography.headlineSmall)
            Text(device.kind.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One titled group of settings on a card. */
@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Confirms forgetting [device], saying what else it clears; shared by Device settings and Devices. */
@Composable
internal fun ForgetDeviceDialog(
    device: SavedDevice,
    detail: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forget ${device.name}?") },
        text = { Text(detail) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Forget")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
