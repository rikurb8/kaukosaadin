package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.PressAction

/**
 * Developer layout: flat panels and a live status log instead of the decorative
 * casing. Drives the same keys and commands as the standard layout.
 */
@Composable
internal fun DebugRemoteScreen(
    contentPadding: PaddingValues,
    devices: List<SavedDevice>,
    current: SavedDevice,
    ready: Boolean,
    busy: Boolean,
    powerEnabled: Boolean,
    txCount: Int,
    log: List<LogLine>,
    navigationEnabled: Boolean,
    onSelect: (SavedDevice) -> Unit,
    onAddDevice: () -> Unit,
    onDevices: () -> Unit,
    onConnect: (() -> Unit)?,
    onApps: (() -> Unit)?,
    onSettings: () -> Unit,
    onGeneralSettings: () -> Unit,
    onPower: () -> Unit,
    onPress: (RemoteKey, PressAction) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "DEBUG LAYOUT · ${current.kind.platform}",
            style = monospace(MaterialTheme.typography.labelMedium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DebugPanel(
            listOf(
                "kind" to current.kind.label,
                "device" to current.name,
                "id" to current.id,
                "ready" to ready.toString(),
                "busy" to busy.toString(),
                "commands" to txCount.toString(),
            ),
        )
        DevicePicker(devices, current, enabled = !busy, onSelect = onSelect, onAddDevice = onAddDevice, onManageDevices = onDevices)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onConnect != null) {
                TextButton(onClick = onConnect, enabled = !busy) { Text(if (ready) "Reconnect TV" else "Connect TV") }
            }
            if (onApps != null) TextButton(onClick = onApps, enabled = !busy) { Text("Apps") }
            TextButton(onClick = onSettings, enabled = !busy) { Text("Device settings") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onGeneralSettings) { Text("General settings") }
            TextButton(onClick = onPower, enabled = powerEnabled && !busy) {
                Text(if (current.kind == DeviceKind.Lg) "Wake" else "Sleep")
            }
        }
        DebugPanel(log.takeLast(DEBUG_LOG_LIMIT).reversed().map { it.time to it.text })
        RemoteKeys(
            dialSize = MinDialSize,
            kind = current.kind,
            navigationEnabled = navigationEnabled,
            onPress = onPress,
        )
    }
}

/** Bordered key/value block in monospace; empty shows a placeholder rather than nothing. */
@Composable
internal fun DebugPanel(rows: List<Pair<String, String>>) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outline, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (rows.isEmpty()) {
            Text(
                "no status yet",
                style = monospace(MaterialTheme.typography.bodySmall),
                color = colors.onSurfaceVariant,
            )
        }
        rows.forEach { (key, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    key,
                    style = monospace(MaterialTheme.typography.bodySmall),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.width(96.dp),
                )
                Text(
                    value,
                    style = monospace(MaterialTheme.typography.bodySmall),
                    color = colors.onSurface,
                )
            }
        }
    }
}

internal fun monospace(base: TextStyle) = base.copy(fontFamily = FontFamily.Monospace)
