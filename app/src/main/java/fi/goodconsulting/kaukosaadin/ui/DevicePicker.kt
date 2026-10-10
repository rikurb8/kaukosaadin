package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.SavedDevice

/**
 * The saved devices the remote can drive, plus ways to add one, manage them and reach General
 * settings; shared by every remote, so the app-wide entries sit in the same menu whichever device is
 * selected.
 */
@Suppress("LongMethod") // The device button and its menu share one open state.
@Composable
internal fun DevicePicker(
    devices: List<SavedDevice>,
    current: SavedDevice,
    enabled: Boolean,
    onSelect: (SavedDevice) -> Unit,
    onAddDevice: () -> Unit,
    onManageDevices: () -> Unit,
    onGeneralSettings: () -> Unit,
    compact: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = if (compact) 56.dp else 76.dp).semantics {
                    contentDescription =
                        "Choose device"
                },
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
            contentPadding = PaddingValues(if (compact) 8.dp else 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KindGlyph(current.kind, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    if (!compact) Text(current.kind.label, style = MaterialTheme.typography.labelSmall)
                    Text(
                        current.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("▾", style = MaterialTheme.typography.titleLarge)
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
            listOf(
                "Add a device" to onAddDevice,
                "Manage devices" to onManageDevices,
                "General settings" to onGeneralSettings,
            ).forEach { (label, onClick) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onClick()
                    },
                )
            }
        }
    }
}
