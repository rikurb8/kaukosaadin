@file:Suppress("MagicNumber") // Glow, glyph and bar geometry are drawn in fractions of their size.

package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fi.goodconsulting.kaukosaadin.device.hue.HueBrightness
import fi.goodconsulting.kaukosaadin.device.hue.HueCommandTarget
import fi.goodconsulting.kaukosaadin.device.hue.HueConnectionState
import fi.goodconsulting.kaukosaadin.device.hue.HueFavorites
import fi.goodconsulting.kaukosaadin.device.hue.HueGroup
import fi.goodconsulting.kaukosaadin.device.hue.HueLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import kotlinx.coroutines.launch

/** Shown when a saved bridge has no stored app key, so the lighting calls cannot be made. */
private const val UNPAIRED_BRIDGE =
    "This bridge is not paired on this phone. Forget it and add it again to store an app key."

/**
 * The Hue bridge's lighting screen: the same device header, status and toolbar as a TV remote,
 * with one card per room and zone where the keypad would be, so selecting a bridge moves the controls
 * rather than the chrome. The bridge is already a saved device, so it opens its own screen instead of
 * adding bulbs to the picker, and nothing here touches the TV remote's keys. [lighting] is null while
 * the bridge has no stored app key. A room or zone with a grouped light has a power key and a
 * brightness bar that sends its one command on release (ticket #10); its lights fold away beneath it,
 * each with the same controls. Favorites (ticket #11) are kept on the phone by [favorites]. While the
 * screen is visible, this screen holds the bridge's live subscription, so a switch or another app
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
    if (controller == null) {
        BridgeShell(remote, padding, ready = false, status = "NOT PAIRED", txFlash = { 0f }) {
            Text(UNPAIRED_BRIDGE, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
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

    BridgeShell(
        remote = remote,
        padding = padding,
        ready = connection is HueConnectionState.Connected,
        status = panelStatus(connection, state),
        // TX stays lit while any command is in flight, like the remote's lamp on a press.
        txFlash = { if (state.busyTargets.isEmpty()) 0f else 1f },
    ) {
        LightingFailureBanner(failureMessage(connection, state))
        if (state.loading) {
            Text("Reading lights, rooms and zones from the bridge…", style = MaterialTheme.typography.bodySmall)
        } else {
            LightingList(
                state = state,
                actions =
                    LightingActions(
                        onToggleLight = { light -> scope.launch { controller.toggle(light) } },
                        onToggleGroup = { group -> scope.launch { controller.toggle(group) } },
                        onToggleFavoriteLight = { light -> controller.toggleFavorite(light) },
                        onToggleFavoriteGroup = { group -> controller.toggleFavorite(group) },
                        onBrightnessDrag = { target, brightness -> controller.dragBrightness(target, brightness) },
                        onBrightnessRelease = { target -> scope.launch { controller.releaseBrightness(target) } },
                    ),
            )
        }
    }
}

/**
 * The bridge uses the shared header with a lighting list instead of a keypad.
 * No power key (a bridge is switched at the wall); rooms keep their text size and scroll.
 */
@Composable
internal fun BridgeShell(
    remote: RemoteActions,
    padding: PaddingValues,
    ready: Boolean,
    status: String,
    txFlash: () -> Float,
    body: @Composable () -> Unit,
) {
    RemoteShell(
        contentPadding = padding,
        devices = remote.devices,
        current = remote.current,
        ready = ready,
        busy = false,
        status = status,
        txFlash = txFlash,
        onSelect = remote.onSelect,
        onAddDevice = remote.onAddDevice,
        onDevices = remote.onDevices,
        onConnect = null,
        onApps = null,
        onSettings = remote.onSettings,
        onGeneralSettings = remote.onGeneralSettings,
        body = { body() },
    )
}

/** The bridge's diagnostic status: the link first, then how much of the house is lit. */
private fun panelStatus(
    connection: HueConnectionState,
    state: LightingState,
): String {
    val lit = state.lights.count { it.on }
    return when {
        connection is HueConnectionState.Connecting -> "LINKING…"
        connection is HueConnectionState.Failed -> "LINK LOST"
        state.loading -> "READING BRIDGE…"
        state.lights.isEmpty() -> "NO LIGHTS"
        lit == 0 -> "ALL ${state.lights.size} LIGHTS OFF"
        else -> "$lit OF ${state.lights.size} LIGHTS ON"
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

/** Every command the screen sends, and the favorite changes it keeps on the phone. */
internal class LightingActions(
    val onToggleLight: (HueLight) -> Unit,
    val onToggleGroup: (HueGroup) -> Unit,
    val onToggleFavoriteLight: (HueLight) -> Unit,
    val onToggleFavoriteGroup: (HueGroup) -> Unit,
    val onBrightnessDrag: (HueCommandTarget, Int) -> Unit,
    val onBrightnessRelease: (HueCommandTarget) -> Unit,
)

/**
 * The bridge's rooms, then its zones, each a card with its lights folded beneath it, favorites first.
 * A light no room holds is listed under Other lights, so every light the bridge reports is reachable.
 */
@Composable
internal fun LightingList(
    state: LightingState,
    actions: LightingActions,
    /** Room and zone ids whose drawer starts open; the screen opens none, a screenshot may. */
    initiallyOpen: Set<String> = emptySet(),
) {
    if (state.rooms.isEmpty() && state.zones.isEmpty() && state.lights.isEmpty()) {
        Text("The bridge reports no lights, rooms or zones.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    SectionLabel("Rooms", state.rooms.size)
    if (state.rooms.isEmpty()) {
        Text("No rooms on this bridge.", style = MaterialTheme.typography.bodySmall)
    }
    state.orderedRooms.forEach { room ->
        // Keyed by id: favoriting reorders the cards, and the open drawer must move with its room.
        key(room.id) { GroupCard(room, "Room", state, actions, room.id in initiallyOpen) }
    }
    if (state.zones.isNotEmpty()) {
        SectionLabel("Zones", state.zones.size)
        state.orderedZones.forEach { zone -> key(zone.id) { GroupCard(zone, "Zone", state, actions, zone.id in initiallyOpen) } }
    }
    val loose = state.lightsOutsideRooms
    if (loose.isNotEmpty()) {
        SectionLabel("Other lights", loose.size)
        LightCard(lit = 0f) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                loose.forEach { light -> key(light.id) { LightRow(light, state, actions) } }
            }
        }
    }
}

/**
 * One room or zone: its name and how many of its lights are on, a favorite star, the power key and a
 * full-width brightness bar for its grouped light, and a drawer of its lights. A room the bridge reports
 * no grouped light for keeps its name and drawer but has no group controls, instead of failing.
 */
@Composable
private fun GroupCard(
    group: HueGroup,
    label: String,
    state: LightingState,
    actions: LightingActions,
    initiallyOpen: Boolean,
) {
    val groupedLight = state.groupedLightFor(group)
    val lights = state.lightsIn(group)
    val target = groupedLight?.let { HueCommandTarget.Group(it.id) }
    val level = barLevel(target?.let { state.brightnessDrafts[it.id] }, groupedLight?.brightness)
    val on = groupedLight?.on == true
    var open by rememberSaveable(group.id) { mutableStateOf(initiallyOpen) }

    LightCard(lit = if (on) 0.35f + 0.65f * level / HueBrightness.MAX else 0f) {
        Column(Modifier.padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupTitle(group.name, groupCaption(label, lights, groupedLight != null), Modifier.weight(1f))
                FavoriteStar(group.id in state.favoriteGroups, group.name) { actions.onToggleFavoriteGroup(group) }
                if (target != null) {
                    Spacer(Modifier.width(4.dp))
                    LampKey(
                        on = on,
                        enabled = target.id !in state.busyTargets,
                        name = group.name,
                        size = 52.dp,
                        onToggle = { actions.onToggleGroup(group) },
                    )
                }
            }
            if (target != null) {
                Spacer(Modifier.height(14.dp))
                BrightnessBar(
                    level = level,
                    on = on,
                    enabled = state.brightnessEnabled(target),
                    height = 44.dp,
                    name = group.name,
                    onDrag = { actions.onBrightnessDrag(target, it) },
                    onRelease = { actions.onBrightnessRelease(target) },
                )
            }
            if (lights.isNotEmpty()) {
                DrawerHandle(lights, open, group.name) { open = !open }
                AnimatedVisibility(
                    visible = open,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(
                        Modifier.padding(top = 4.dp, bottom = 14.dp, end = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        lights.forEach { light -> key(light.id) { LightRow(light, state, actions) } }
                    }
                }
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** A card's name over its caption. */
@Composable
private fun GroupTitle(
    name: String,
    caption: String,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

/** "Room · 2 of 3 on", or why the card has no group controls. */
private fun groupCaption(
    label: String,
    lights: List<HueLight>,
    controllable: Boolean,
): String {
    val lit = lights.count { it.on }
    val detail =
        when {
            !controllable -> "no group control on the bridge"
            lights.isEmpty() -> null
            lit == 0 -> "all off"
            lit == lights.size -> "all on"
            else -> "$lit of ${lights.size} on"
        }
    return listOfNotNull(label, detail).joinToString(" · ")
}

/** One light inside a card: its LED, name, favorite star and power key above a slim brightness bar. */
@Composable
private fun LightRow(
    light: HueLight,
    state: LightingState,
    actions: LightingActions,
) {
    val target = HueCommandTarget.Light(light.id)
    val level = barLevel(state.brightnessDrafts[light.id], light.brightness)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LampLed(light.on, level, Modifier.padding(start = 2.dp, end = 12.dp))
            Text(
                light.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FavoriteStar(light.id in state.favoriteLights, light.name, size = 18.dp) { actions.onToggleFavoriteLight(light) }
            LampKey(
                on = light.on,
                enabled = light.id !in state.busyTargets,
                name = light.name,
                size = 36.dp,
                onToggle = { actions.onToggleLight(light) },
            )
        }
        BrightnessBar(
            level = level,
            on = light.on,
            enabled = state.brightnessEnabled(target),
            height = 26.dp,
            name = light.name,
            modifier = Modifier.padding(start = 24.dp, top = 4.dp),
            onDrag = { actions.onBrightnessDrag(target, it) },
            onRelease = { actions.onBrightnessRelease(target) },
        )
    }
}
