package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.PressAction
import java.time.LocalTime
import java.util.Locale

/**
 * Wider than this (tablets, unfolded foldables) the remote stays phone-sized
 * as a framed casing on a backdrop; narrower, the casing is the whole screen.
 */
internal val FramedMinWidth = 480.dp
internal val FramedMaxWidth = 420.dp

/**
 * Drives [current], one of the saved [devices]; controls require verified registration (LG) or
 * a saved pairing (Apple TV). [onConnect] is null for devices that verify per press,
 * and [onApps] is null for devices with no launchable-app list.
 */
@Suppress("LongMethod") // Hands the same callbacks to either the Debug or the Standard layout.
@Composable
internal fun RemoteScreen(
    contentPadding: PaddingValues,
    devices: List<SavedDevice>,
    current: SavedDevice,
    layout: AppLayout,
    ready: Boolean,
    busy: Boolean,
    powerEnabled: Boolean,
    status: String,
    onSelect: (SavedDevice) -> Unit,
    onAddDevice: () -> Unit,
    onDevices: () -> Unit,
    onConnect: (() -> Unit)?,
    onApps: (() -> Unit)?,
    onSettings: () -> Unit,
    onGeneralSettings: () -> Unit,
    onPower: () -> Unit,
    onKey: (RemoteKey, PressAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var txCount by remember { mutableIntStateOf(0) }
    val navigationEnabled = ready && !busy

    fun send(
        key: RemoteKey,
        action: PressAction = PressAction.Tap,
    ) {
        txCount++
        onKey(key, action)
    }

    fun power() {
        txCount++
        onPower()
    }

    val txFlash = rememberTxFlash(txCount)
    val log = rememberStatusLog(status)

    if (layout == AppLayout.Debug) {
        DebugRemoteScreen(
            contentPadding = contentPadding,
            devices = devices,
            current = current,
            ready = ready,
            busy = busy,
            powerEnabled = powerEnabled,
            txCount = txCount,
            log = log,
            navigationEnabled = navigationEnabled,
            onSelect = onSelect,
            onAddDevice = onAddDevice,
            onDevices = onDevices,
            onConnect = onConnect,
            onApps = onApps,
            onSettings = onSettings,
            onGeneralSettings = onGeneralSettings,
            onPower = { power() },
            onPress = { key, action -> send(key, action) },
        )
        return
    }

    RemoteShell(
        contentPadding = contentPadding,
        devices = devices,
        current = current,
        ready = ready,
        busy = busy,
        status = status,
        txFlash = { txFlash.value },
        deck = {
            PowerDeck(
                ready = ready,
                powerEnabled = powerEnabled && !busy,
                kind = current.kind,
                txFlash = { txFlash.value },
                onPower = { power() },
            )
        },
        onSelect = onSelect,
        onAddDevice = onAddDevice,
        onDevices = onDevices,
        onConnect = onConnect,
        onApps = onApps,
        onSettings = onSettings,
        onGeneralSettings = onGeneralSettings,
        modifier = modifier,
        body = { dialSize ->
            RemoteKeys(
                dialSize = dialSize,
                kind = current.kind,
                navigationEnabled = navigationEnabled,
                onPress = { key, action -> send(key, action) },
            )
        },
    )
}

/**
 * The casing every remote shares: the picker, the power deck, the VFD readout, the toolbar, the
 * kind's own [body] and the footer, all in the same places whichever device is selected, so switching
 * devices moves the controls rather than the chrome. [fitHeight] scales the whole remote down to fit
 * when it can, which suits a keypad; a list keeps its own size and scrolls instead.
 */
@Composable
internal fun RemoteShell(
    contentPadding: PaddingValues,
    devices: List<SavedDevice>,
    current: SavedDevice,
    ready: Boolean,
    busy: Boolean,
    status: String,
    txFlash: () -> Float,
    deck: @Composable () -> Unit,
    onSelect: (SavedDevice) -> Unit,
    onAddDevice: () -> Unit,
    onDevices: () -> Unit,
    onConnect: (() -> Unit)?,
    onApps: (() -> Unit)?,
    onSettings: () -> Unit,
    onGeneralSettings: () -> Unit,
    modifier: Modifier = Modifier,
    fitHeight: Boolean = true,
    body: @Composable (dialSize: Dp) -> Unit,
) {
    var showStatus by remember(current.id) { mutableStateOf(false) }
    if (showStatus) StatusDialog(current, status) { showStatus = false }

    RemoteCasing(contentPadding, modifier, fitHeight) { dialSize ->
        deck()
        StatusControls(
            devices = devices,
            current = current,
            status = status,
            ready = ready,
            busy = busy,
            txFlash = txFlash,
            onSelect = onSelect,
            onAddDevice = onAddDevice,
            onDevices = onDevices,
            onConnect = onConnect,
            onApps = onApps,
            onSettings = onSettings,
            onGeneralSettings = onGeneralSettings,
            onShowStatus = { showStatus = true },
        )
        body(dialSize)
        RemoteFooter(current.kind)
    }
}

/** TX lamp flash: snaps to 1 then decays on every command count change. */
@Composable
private fun rememberTxFlash(txCount: Int): Animatable<Float, AnimationVector1D> {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(txCount) {
        if (txCount > 0) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(durationMillis = 380, easing = LinearOutSlowInEasing))
        }
    }
    return flash
}

/**
 * In-memory log of status lines for the debug layout, owned by the caller so it survives
 * switching layouts. ponytail: per remote session; persist to prefs if it must survive restarts.
 */
@Composable
private fun rememberStatusLog(status: String): List<LogLine> {
    val log = remember { mutableStateListOf<LogLine>() }
    LaunchedEffect(status) { log += LogLine(LocalTime.now().format(LogClock), status) }
    return log
}

/**
 * Frames the remote: on a phone the phone *is* the remote and the casing fills the
 * screen behind the system bars; wide screens keep a phone-sized casing on the
 * desk backdrop instead of stretching the keys across it. The content slot receives
 * the dial size the casing dictates.
 */
@Composable
private fun RemoteCasing(
    contentPadding: PaddingValues,
    modifier: Modifier,
    fitHeight: Boolean,
    content: @Composable (dialSize: Dp) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val casingBrush = Brush.verticalGradient(listOf(colors.surface.tint(0.05f), colors.surface.shade(0.06f)))
    val casingShape = RoundedCornerShape(32.dp)
    val screwColor = colors.outline
    BoxWithConstraints(modifier.fillMaxSize()) {
        val framed = maxWidth >= FramedMinWidth
        val casingWidth = if (framed) FramedMaxWidth else maxWidth
        val dialSize = (casingWidth * 0.62f).coerceIn(MinDialSize, MaxDialSize)
        // Captured for fitToHeight: the scroll container's ColumnScope shadows the
        // BoxWithConstraints scope inside the content lambda.
        val viewportHeight = maxHeight
        val visibleHeight = viewportHeight - contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding()
        val casing =
            if (framed) {
                Modifier
                    .width(FramedMaxWidth)
                    .shadow(elevation = 16.dp, shape = casingShape)
                    .clip(casingShape)
                    .background(casingBrush)
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), colors.outline)),
                        shape = casingShape,
                    ).drawBehind {
                        val inset = 15.dp.toPx()
                        val radius = 4.5.dp.toPx()
                        screw(Offset(inset, inset), radius, 35f, screwColor)
                        screw(Offset(size.width - inset, inset), radius, -20f, screwColor)
                        screw(Offset(inset, size.height - inset), radius, 80f, screwColor)
                        screw(Offset(size.width - inset, size.height - inset), radius, 10f, screwColor)
                    }
            } else {
                // Fill the visible height so spare room spreads between
                // sections rather than pooling under the grille.
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = visibleHeight)
            }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (framed) Modifier else Modifier.background(casingBrush))
                    // Fitting keeps the whole remote on screen while it can; past the
                    // scale floor the rest scrolls, so the system font setting keeps
                    // growing the text instead of being scaled away.
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier =
                    Modifier
                        .then(if (fitHeight) Modifier.fitToHeight(viewportHeight) else Modifier)
                        .padding(contentPadding)
                        .then(if (framed) Modifier.padding(horizontal = 16.dp, vertical = 8.dp) else Modifier)
                        .then(casing)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = if (framed) Arrangement.spacedBy(18.dp) else spacedEvenly(atLeast = 18.dp),
            ) {
                content(dialSize)
            }
        }
    }
}

/** Device picker, VFD readout and the remote's toolbar. */
@Composable
private fun StatusControls(
    devices: List<SavedDevice>,
    current: SavedDevice,
    status: String,
    ready: Boolean,
    busy: Boolean,
    txFlash: () -> Float,
    onSelect: (SavedDevice) -> Unit,
    onAddDevice: () -> Unit,
    onDevices: () -> Unit,
    onConnect: (() -> Unit)?,
    onApps: (() -> Unit)?,
    onSettings: () -> Unit,
    onGeneralSettings: () -> Unit,
    onShowStatus: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DevicePicker(devices, current, enabled = !busy, onSelect, onAddDevice, onManageDevices = onDevices, onGeneralSettings)
        VfdDisplay(
            kind = current.kind,
            deviceName = current.name,
            ready = ready,
            status = status.uppercase(Locale.US),
            txFlash = txFlash,
        )
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Stable footprint; the full diagnostic remains available on tap.
            minLines = 3,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Show full status", onClick = onShowStatus),
        )
        RemoteToolbar(ready, busy, onConnect, onApps, onSettings)
    }
}

/**
 * The row under every remote's display, in every layout: the kind's own actions on the left (Connect
 * for an LG TV, Apps for an Apple TV, none for a bridge) and Device settings always at the right edge,
 * so it is in the same place whichever device is selected. App-wide settings live in the picker.
 */
@Composable
internal fun RemoteToolbar(
    ready: Boolean,
    busy: Boolean,
    onConnect: (() -> Unit)?,
    onApps: (() -> Unit)?,
    onSettings: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onConnect != null) {
            TextButton(onClick = onConnect, enabled = !busy) { Text(if (ready) "Reconnect TV" else "Connect TV") }
        }
        if (onApps != null) TextButton(onClick = onApps, enabled = !busy) { Text("Apps") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSettings, enabled = !busy) { Text("Device settings") }
    }
}

/**
 * One line about the kind's keys, then the speaker grille and model engraving at the tail of the
 * casing. Every kind prints exactly one line here, like the spacer that stands in for LG's missing
 * Play/Pause row: a taller footer on one kind would make fitToHeight shrink that whole remote.
 */
@Composable
private fun RemoteFooter(kind: DeviceKind) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            footerNote(kind),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SpeakerGrille()
        EngravedLabel("MODEL KS-01 · UNIVERSAL")
    }
}

/** The footer's line for [kind]: LG's power is wake-only, and Apple TV's Back and Home take gestures (see [gestures]). */
private fun footerNote(kind: DeviceKind): String =
    when (kind) {
        DeviceKind.Lg -> "Wake only · TV power is not monitored"
        DeviceKind.AppleTv -> "Double-tap or hold Back and Home for more"
        DeviceKind.Hue -> ""
    }

/** Full status text; the remote itself only shows a trimmed preview. */
@Composable
private fun StatusDialog(
    device: SavedDevice,
    status: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${device.name} status") },
        text = { Text(status) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
