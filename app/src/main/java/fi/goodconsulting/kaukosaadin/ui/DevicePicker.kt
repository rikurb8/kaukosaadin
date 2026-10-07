package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.SavedDevice

/** The saved devices the remote can drive, plus ways to add one or manage them; shared by every layout. */
@Composable
internal fun DevicePicker(
    devices: List<SavedDevice>,
    current: SavedDevice,
    enabled: Boolean,
    onSelect: (SavedDevice) -> Unit,
    onAddDevice: () -> Unit,
    onManageDevices: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KindGlyph(current.kind, size = 28.dp)
                Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("▾")
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            devices.forEach { device ->
                DropdownMenuItem(
                    leadingIcon = { KindGlyph(device.kind, size = 32.dp) },
                    text = {
                        Column {
                            Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                device.kind.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = {
                        if (device.id == current.id) {
                            CheckGlyph(MaterialTheme.colorScheme.primary)
                        } else {
                            Box(Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(device)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Add a device") },
                onClick = {
                    open = false
                    onAddDevice()
                },
            )
            DropdownMenuItem(
                text = { Text("Manage devices") },
                onClick = {
                    open = false
                    onManageDevices()
                },
            )
        }
    }
}
