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
import androidx.compose.material3.Slider
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
import fi.goodconsulting.kaukosaadin.device.hue.HueFavorites
import fi.goodconsulting.kaukosaadin.device.hue.HueGroupedLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import fi.goodconsulting.kaukosaadin.device.hue.HueRoom
import fi.goodconsulting.kaukosaadin.device.hue.HueTarget
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Shown when a saved bridge has no stored app key, so the lighting calls cannot be made. */
private const val UNPAIRED_BRIDGE =
    "This bridge is not paired on this phone. Forget it and add it again to store an app key."

/** The lowest and highest brightness a Hue light or group accepts, and the slider's full range. */
private const val MIN_BRIGHTNESS = 0
private const val MAX_BRIGHTNESS = 100

/**
 * The Hue bridge's lighting screen: the shared device picker, the bridge's rooms and lights, and the
 * common settings. The bridge is already a saved device, so it opens its own screen instead of adding
 * bulbs to the picker, and nothing here touches the TV remote's keys. [lighting] is null while the
 * bridge has no stored app key. Each light, and each room with a grouped light, has an on/off switch
 * and a brightness slider that sends its one command on release (ticket #10), and a favorite control
 * whose change [favorites] keeps on the phone (ticket #11). #12 adds the live subscription to
 * [LightingContent].
 */
@Composable
internal fun LightingScreen(
    padding: PaddingValues,
    remote: RemoteActions,
    lighting: HueLighting?,
    favorites: HueFavorites,
) {
    val controller = remember(lighting, favorites) { lighting?.let { LightingController(it, favorites) } }
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
            state = state,
            onToggleLight = { light -> scope.launch { controller.toggle(light) } },
            onToggleRoom = { room -> scope.launch { controller.toggle(room) } },
            onBrightnessDrag = { target, brightness -> controller.dragBrightness(target, brightness) },
            onBrightnessRelease = { target -> scope.launch { controller.releaseBrightness(target) } },
        )
    }
}

/** The failure to show the operator: a failed bridge read, command, or live connection. */
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

/** The bridge's rooms and lights. A room with a grouped light is controlled as one unit, like a light. */
@Composable
private fun LightingList(
    state: LightingState,
    onToggleLight: (HueLight) -> Unit,
    onToggleRoom: (HueRoom) -> Unit,
    onBrightnessDrag: (HueTarget, Int) -> Unit,
    onBrightnessRelease: (HueTarget) -> Unit,
) {
    if (state.rooms.isEmpty() && state.lights.isEmpty()) {
        Text("The bridge reports no lights or rooms.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text("Rooms", style = MaterialTheme.typography.titleMedium)
    if (state.rooms.isEmpty()) {
        Text("No rooms on this bridge.", style = MaterialTheme.typography.bodySmall)
    } else {
        state.rooms.forEach { room ->
            LightingRoomRow(
                room = room,
                group = state.groupedLightFor(room),
                state = state,
                actions =
                    LightingRowActions(
                        onToggle = { onToggleRoom(room) },
                        onBrightnessDrag = onBrightnessDrag,
                        onBrightnessRelease = onBrightnessRelease,
                    ),
            )
        }
    }
    Text("Lights", style = MaterialTheme.typography.titleMedium)
    if (state.lights.isEmpty()) {
        Text("No lights on this bridge.", style = MaterialTheme.typography.bodySmall)
    } else {
        state.lights.forEach { light ->
            LightingLightRow(
                light = light,
                state = state,
                actions =
                    LightingRowActions(
                        onToggle = { onToggleLight(light) },
                        onBrightnessDrag = onBrightnessDrag,
                        onBrightnessRelease = onBrightnessRelease,
                    ),
            )
        }
    }
}

/** The commands one controlled row sends: its switch, and its brightness slider's drag and release. */
private data class LightingRowActions(
    val onToggle: () -> Unit,
    val onBrightnessDrag: (HueTarget, Int) -> Unit,
    val onBrightnessRelease: (HueTarget) -> Unit,
)

/** One controlled row's rendered state: what it is called, whether it is on, and the slider's value. */
private data class LightingRowState(
    val name: String,
    val on: Boolean,
    val brightness: Double?,
    val draft: Int?,
    val enabled: Boolean,
    val busy: Boolean,
)

/**
 * One light: its name and on/off switch, above a brightness slider. The slider is disabled while the
 * light is off, and only its release sends a command — the drag only moves [LightingState.brightnessDrafts].
 */
@Composable
private fun LightingLightRow(
    light: HueLight,
    state: LightingState,
    actions: LightingRowActions,
) {
    val target = HueTarget.Light(light.id)
    LightingRowBody(
        row =
            LightingRowState(
                name = light.name,
                on = light.on,
                brightness = light.brightness,
                draft = state.brightnessDrafts[light.id],
                enabled = state.brightnessEnabled(target),
                busy = light.id in state.busyTargets,
            ),
        target = target,
        actions = actions,
    )
}

/**
 * One room. With a grouped light it works exactly like a light, so the room moves as a unit; a room
 * the bridge reports no grouped light for is listed without controls instead of failing.
 */
@Composable
private fun LightingRoomRow(
    room: HueRoom,
    group: HueGroupedLight?,
    state: LightingState,
    actions: LightingRowActions,
) {
    if (group == null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(room.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Room",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val target = HueTarget.Group(group.id)
    LightingRowBody(
        row =
            LightingRowState(
                name = room.name,
                on = group.on,
                brightness = group.brightness,
                draft = state.brightnessDrafts[group.id],
                enabled = state.brightnessEnabled(target),
                busy = group.id in state.busyTargets,
            ),
        target = target,
        actions = actions,
    )
}

/** A controlled row: its name and on/off switch, and a brightness slider that emits only on release. */
@Composable
private fun LightingRowBody(
    row: LightingRowState,
    target: HueTarget,
    actions: LightingRowActions,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(row.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Switch(checked = row.on, onCheckedChange = { actions.onToggle() }, enabled = !row.busy)
        }
        Slider(
            value = (row.draft ?: row.brightness?.roundToInt() ?: MIN_BRIGHTNESS).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS).toFloat(),
            onValueChange = { actions.onBrightnessDrag(target, it.roundToInt()) },
            onValueChangeFinished = { actions.onBrightnessRelease(target) },
            enabled = row.enabled,
            valueRange = MIN_BRIGHTNESS.toFloat()..MAX_BRIGHTNESS.toFloat(),
        )
    }
}
