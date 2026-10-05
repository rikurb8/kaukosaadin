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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceStore
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import kotlinx.coroutines.launch

/**
 * Settings for one saved device: the common rename and forget controls the shell owns, plus the
 * extras [controls] contributes for the device's kind.
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
                    // The one forget path: the kind clears its local state first, then the saved
                    // device goes. A kind that fails leaves the saved device in place.
                    val failure = controls.forget()
                    if (failure != null) {
                        message = failure
                    } else if (store.forget(device.id)) {
                        onBack()
                    } else {
                        message = "Could not forget the device. Try again."
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
        controls.Settings(device, busy, ::run)
        TextButton(enabled = !busy, onClick = { confirmForget = true }) { Text("Forget device") }
        TextButton(onClick = onBack) { Text("Done") }
    }
}

@Composable
private fun ForgetDeviceDialog(
    device: SavedDevice,
    detail: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forget ${device.name}?") },
        text = { Text(detail) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Forget") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
