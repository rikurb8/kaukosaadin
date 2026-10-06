package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.hue.HueConnectionState
import fi.goodconsulting.kaukosaadin.device.hue.HueLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import fi.goodconsulting.kaukosaadin.device.hue.HueRoom
import kotlinx.coroutines.launch

/** Shown when a saved bridge has no stored app key, so the lighting calls cannot be made. */
private const val UNPAIRED_BRIDGE =
    "This bridge is not paired on this phone. Forget it and add it again to store an app key."

/**
 * The Hue bridge's lighting screen: the shared device picker, the bridge's rooms and lights, and the
 * common settings. The bridge is already a saved device, so it opens its own screen instead of adding
 * bulbs to the picker, and nothing here touches the TV remote's keys. [lighting] is null while the
 * bridge has no stored app key. Ticket #10 adds grouped on/off and brightness to the rows, #11
 * favorites to [LightingList], and #12 the live subscription to [LightingContent].
 */
@Composable
internal fun LightingScreen(
    padding: PaddingValues,
    remote: RemoteActions,
    lighting: HueLighting?,
) {
    val controller = remember(lighting) { lighting?.let { LightingController(it) } }
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DevicePicker(
            devices = remote.devices,
            current = remote.current,
            enabled = true,
            onSelect = remote.onSelect,
            onAddDevice = remote.onAddDevice,
        )
        Text(remote.current.name, style = MaterialTheme.typography.titleLarge)
        Text(
            "${remote.current.kind.label} · ${remote.current.host}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (controller == null) {
            Text(UNPAIRED_BRIDGE, style = MaterialTheme.typography.bodyMedium)
        } else {
            LightingContent(controller)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = remote.onSettings) { Text("Device settings") }
            TextButton(onClick = remote.onGeneralSettings) { Text("General settings") }
        }
    }
}

/** The stateful part: fetches once when the screen opens and renders the banner and the list. */
@Composable
private fun LightingContent(controller: LightingController) {
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val connection by controller.connection.collectAsState()
    val failure = (connection as? HueConnectionState.Failed)?.message ?: state.failure

    // Fetch the bridge's state when the screen opens; ticket #12 moves this to the screen lifecycle.
    LaunchedEffect(controller) { controller.load() }

    LightingFailureBanner(failure)
    if (state.loading) {
        Text("Reading lights and rooms from the bridge…", style = MaterialTheme.typography.bodySmall)
    } else {
        LightingList(
            rooms = state.rooms,
            lights = state.lights,
            busyLights = state.busyLights,
            onToggle = { light -> scope.launch { controller.toggle(light) } },
        )
    }
}

/** The failure to show the operator: a failed bridge read, on/off command, or live connection. */
@Composable
private fun LightingFailureBanner(message: String?) {
    if (message == null) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** The bridge's rooms and lights. Ticket #10 controls the rooms here; #11 orders favorites first. */
@Composable
private fun LightingList(
    rooms: List<HueRoom>,
    lights: List<HueLight>,
    busyLights: Set<String>,
    onToggle: (HueLight) -> Unit,
) {
    if (rooms.isEmpty() && lights.isEmpty()) {
        Text("The bridge reports no lights or rooms.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text("Rooms", style = MaterialTheme.typography.titleMedium)
    if (rooms.isEmpty()) {
        Text("No rooms on this bridge.", style = MaterialTheme.typography.bodySmall)
    } else {
        rooms.forEach { room -> LightingRoomRow(room) }
    }
    Text("Lights", style = MaterialTheme.typography.titleMedium)
    if (lights.isEmpty()) {
        Text("No lights on this bridge.", style = MaterialTheme.typography.bodySmall)
    } else {
        lights.forEach { light ->
            LightingLightRow(light, busy = light.id in busyLights, onToggle = { onToggle(light) })
        }
    }
}

/** One light: its name and an on/off switch, disabled while its command is in flight. */
@Composable
private fun LightingLightRow(
    light: HueLight,
    busy: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(light.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Switch(checked = light.on, onCheckedChange = { onToggle() }, enabled = !busy)
    }
}

/** One room the bridge reports; it is listed here, and ticket #10 adds its grouped on/off and brightness. */
@Composable
private fun LightingRoomRow(room: HueRoom) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(room.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "Room",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
