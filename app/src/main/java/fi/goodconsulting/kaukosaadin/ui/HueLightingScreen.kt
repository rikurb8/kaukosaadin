package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.LocalActivity
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fi.goodconsulting.kaukosaadin.device.hue.HueBrightness
import fi.goodconsulting.kaukosaadin.device.hue.HueCommandTarget
import fi.goodconsulting.kaukosaadin.device.hue.HueFavorites
import fi.goodconsulting.kaukosaadin.device.hue.HueGroup
import fi.goodconsulting.kaukosaadin.device.hue.HueLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Shown when a saved bridge has no stored app key, so the lighting calls cannot be made. */
private const val UNPAIRED_BRIDGE =
    "This bridge is not paired on this phone. Forget it and add it again to store an app key."

/**
 * The Hue bridge's lighting screen: the shared device picker, the bridge's rooms, zones and lights,
 * and the common settings. The bridge is already a saved device, so it opens its own screen instead
 * of adding bulbs to the picker, and nothing here touches the TV remote's keys. [lighting] is null
 * while the bridge has no stored app key. Each light, and each room or zone with a grouped light,
 * has an on/off switch and a brightness slider that sends its one command on release (ticket #10),
 * and a favorite control whose change [favorites] keeps on the phone (ticket #11). While the screen
 * is visible, [LightingContent] holds the bridge's live subscription, so a switch or another app
 * shows up here too.
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

/** The stateful part: refreshes the bridge's state while the screen is visible and renders the banner and the list. */
@Composable
private fun LightingContent(controller: LightingController) {
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    val state by controller.state.collectAsState()
    val connection by controller.connection.collectAsState()

    // Opening the lighting screen connects the live subscription and refreshes the bridge's state;
    // leaving or backgrounding releases it, and coming back repeats both. A new controller restarts it.
    LaunchedEffect(activity, controller) {
        if (activity is LifecycleOwner) {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { controller.live() }
        }
    }

    LightingFailureBanner(failureMessage(connection, state))
    if (state.loading) {
        Text("Reading lights, rooms and zones from the bridge…", style = MaterialTheme.typography.bodySmall)
    } else {
        LightingList(
            state = state,
            onToggleLight = { light -> scope.launch { controller.toggle(light) } },
            onToggleGroup = { group -> scope.launch { controller.toggle(group) } },
            onToggleFavoriteLight = { light -> controller.toggleFavorite(light) },
            onToggleFavoriteGroup = { group -> controller.toggleFavorite(group) },
            onBrightnessDrag = { target, brightness -> controller.dragBrightness(target, brightness) },
            onBrightnessRelease = { target -> scope.launch { controller.releaseBrightness(target) } },
        )
    }
}

/** The failure shared by the lighting screen and the Super remote's lighting section: a failed bridge read, command or live connection. */
@Composable
internal fun LightingFailureBanner(message: String?) {
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

/**
 * The bridge's rooms, zones and lights, favorites first. A room or zone with a grouped light is
 * controlled as one unit, like a light; each is listed under its own heading so the two are told
 * apart. The favorite controls write to the phone, not the bridge.
 */
@Composable
private fun LightingList(
    state: LightingState,
    onToggleLight: (HueLight) -> Unit,
    onToggleGroup: (HueGroup) -> Unit,
    onToggleFavoriteLight: (HueLight) -> Unit,
    onToggleFavoriteGroup: (HueGroup) -> Unit,
    onBrightnessDrag: (HueCommandTarget, Int) -> Unit,
    onBrightnessRelease: (HueCommandTarget) -> Unit,
) {
    if (state.rooms.isEmpty() && state.zones.isEmpty() && state.lights.isEmpty()) {
        Text("The bridge reports no lights, rooms or zones.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    LightingGroupSection(
        title = "Rooms",
        label = "Room",
        empty = "No rooms on this bridge.",
        groups = state.orderedRooms,
        state = state,
        onToggleGroup = onToggleGroup,
        onToggleFavoriteGroup = onToggleFavoriteGroup,
        onBrightnessDrag = onBrightnessDrag,
        onBrightnessRelease = onBrightnessRelease,
    )
    LightingGroupSection(
        title = "Zones",
        label = "Zone",
        empty = "No zones on this bridge.",
        groups = state.orderedZones,
        state = state,
        onToggleGroup = onToggleGroup,
        onToggleFavoriteGroup = onToggleFavoriteGroup,
        onBrightnessDrag = onBrightnessDrag,
        onBrightnessRelease = onBrightnessRelease,
    )
    Text("Lights", style = MaterialTheme.typography.titleMedium)
    if (state.lights.isEmpty()) {
        Text("No lights on this bridge.", style = MaterialTheme.typography.bodySmall)
    } else {
        state.orderedLights.forEach { light ->
            LightingLightRow(
                light = light,
                state = state,
                actions =
                    LightingRowActions(
                        onToggle = { onToggleLight(light) },
                        onToggleFavorite = { onToggleFavoriteLight(light) },
                        onBrightnessDrag = onBrightnessDrag,
                        onBrightnessRelease = onBrightnessRelease,
                    ),
            )
        }
    }
}

/** One heading and its group rows, favourites first, or the [empty] line when the bridge reports none. */
@Composable
private fun LightingGroupSection(
    title: String,
    label: String,
    empty: String,
    groups: List<HueGroup>,
    state: LightingState,
    onToggleGroup: (HueGroup) -> Unit,
    onToggleFavoriteGroup: (HueGroup) -> Unit,
    onBrightnessDrag: (HueCommandTarget, Int) -> Unit,
    onBrightnessRelease: (HueCommandTarget) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (groups.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.bodySmall)
        return
    }
    groups.forEach { group ->
        LightingGroupRow(
            group = group,
            label = label,
            state = state,
            actions =
                LightingRowActions(
                    onToggle = { onToggleGroup(group) },
                    onToggleFavorite = { onToggleFavoriteGroup(group) },
                    onBrightnessDrag = onBrightnessDrag,
                    onBrightnessRelease = onBrightnessRelease,
                ),
        )
    }
}

/** The commands one controlled row sends: its switch, its favorite control, and its brightness slider's drag and release. */
private data class LightingRowActions(
    val onToggle: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onBrightnessDrag: (HueCommandTarget, Int) -> Unit,
    val onBrightnessRelease: (HueCommandTarget) -> Unit,
)

/** One controlled row's rendered state: what it is called, whether it is on, and the slider's value. */
private data class LightingRowState(
    val name: String,
    val on: Boolean,
    val brightness: Double?,
    val draft: Int?,
    val enabled: Boolean,
    val busy: Boolean,
    val favorite: Boolean,
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
    val target = HueCommandTarget.Light(light.id)
    LightingRowBody(
        row =
            LightingRowState(
                name = light.name,
                on = light.on,
                brightness = light.brightness,
                draft = state.brightnessDrafts[light.id],
                enabled = state.brightnessEnabled(target),
                busy = light.id in state.busyTargets,
                favorite = light.id in state.favoriteLights,
            ),
        target = target,
        actions = actions,
    )
}

/**
 * One room or zone, listed under its own heading. With a grouped light it works exactly like a light,
 * so the group moves as a unit; one the bridge reports no grouped light for is listed without controls
 * instead of failing, labelled [label] so the operator can tell a room from a zone.
 */
@Composable
private fun LightingGroupRow(
    group: HueGroup,
    label: String,
    state: LightingState,
    actions: LightingRowActions,
) {
    val groupedLight = state.groupedLightFor(group)
    if (groupedLight == null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(group.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FavoriteButton(favorite = group.id in state.favoriteGroups, onClick = actions.onToggleFavorite)
        }
        return
    }
    val target = HueCommandTarget.Group(groupedLight.id)
    LightingRowBody(
        row =
            LightingRowState(
                name = group.name,
                on = groupedLight.on,
                brightness = groupedLight.brightness,
                draft = state.brightnessDrafts[groupedLight.id],
                enabled = state.brightnessEnabled(target),
                busy = groupedLight.id in state.busyTargets,
                favorite = group.id in state.favoriteGroups,
            ),
        target = target,
        actions = actions,
    )
}

/** A controlled row: its name, its on/off switch and favorite control, and a brightness slider that emits only on release. */
@Composable
private fun LightingRowBody(
    row: LightingRowState,
    target: HueCommandTarget,
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
            // Last in the row: the favorite control's text changes width, so anywhere earlier it
            // would shift the switch out from under the operator's finger.
            FavoriteButton(favorite = row.favorite, onClick = actions.onToggleFavorite)
        }
        Slider(
            value =
                (row.draft ?: row.brightness?.roundToInt() ?: HueBrightness.MIN)
                    .coerceIn(HueBrightness.MIN, HueBrightness.MAX)
                    .toFloat(),
            onValueChange = { actions.onBrightnessDrag(target, it.roundToInt()) },
            onValueChangeFinished = { actions.onBrightnessRelease(target) },
            enabled = row.enabled,
            valueRange = HueBrightness.MIN.toFloat()..HueBrightness.MAX.toFloat(),
        )
    }
}

/**
 * The favorite control of one row: it names the action it performs, so the operator can tell whether
 * the row is already kept. It is never disabled: keeping a light, room or zone writes to the phone,
 * so it needs no bridge and no command slot.
 */
@Composable
private fun FavoriteButton(
    favorite: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) { Text(if (favorite) "Unfavorite" else "Favorite") }
}
