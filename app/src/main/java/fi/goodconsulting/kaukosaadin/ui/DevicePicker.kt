package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import fi.goodconsulting.kaukosaadin.device.SavedDevice

/** The saved devices the remote can drive, plus a way to add another; shared by every layout. */
@Composable
internal fun DevicePicker(
    devices: List<SavedDevice>,
    current: SavedDevice,
    enabled: Boolean,
    onSelect: (SavedDevice) -> Unit,
    onAddDevice: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("${current.name} · ${current.kind.label} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(if (device.id == current.id) "✓ ${device.name}" else device.name) },
                    trailingIcon = { Text(device.kind.label) },
                    onClick = {
                        open = false
                        onSelect(device)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Add device…") },
                onClick = {
                    open = false
                    onAddDevice()
                },
            )
        }
    }
}
