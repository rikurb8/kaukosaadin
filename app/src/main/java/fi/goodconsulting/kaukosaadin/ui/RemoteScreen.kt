package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.CompanionSkipSupport
import fi.goodconsulting.kaukosaadin.device.companion.PressAction
import java.time.LocalTime

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
    skipSupport: CompanionSkipSupport = CompanionSkipSupport(),
    onSkip: ((Double) -> Unit)? = null,
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

    val skip: ((Double) -> Unit)? =
        onSkip?.let { send ->
            { seconds ->
                txCount++
                send(seconds)
            }
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
            skipSupport = skipSupport,
            onSkip = skip,
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
            PowerKey(
                enabled = powerEnabled && !busy,
                kind = current.kind,
                onClick = { power() },
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
                skipSupport = skipSupport,
                onSkip = skip,
                fitToScreen = true,
            )
        },
    )
}

/** Shared device header and status, followed by the kind's controls: the TV kinds share one fitted
 * viewport, while the bridge keeps a scrolling list that never shrinks the rooms' text. */
@Composable
internal fun RemoteShell(
    contentPadding: PaddingValues,
    devices: List<SavedDevice>,
    current: SavedDevice,
    ready: Boolean,
    busy: Boolean,
    status: String,
    txFlash: () -> Float,
    onSelect: (SavedDevice) -> Unit,
    onAddDevice: () -> Unit,
    onDevices: () -> Unit,
    onConnect: (() -> Unit)?,
    onApps: (() -> Unit)?,
    onSettings: () -> Unit,
    onGeneralSettings: () -> Unit,
    modifier: Modifier = Modifier,
    deck: @Composable () -> Unit = {},
    body: @Composable (dialSize: Dp) -> Unit,
) {
    var showStatus by remember(current.id) { mutableStateOf(false) }
    if (showStatus) StatusDialog(current, status) { showStatus = false }

    if (current.kind != DeviceKind.Hue) {
        RemoteViewport(contentPadding, modifier, body) { landscape ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        DevicePicker(devices, current, !busy, onSelect, onAddDevice, onDevices, onGeneralSettings, compact = true)
                    }
                    deck()
                }
                if (landscape) {
                    ConnectionStrip(ready, busy, status, txFlash, compact = true) { showStatus = true }
                    RemoteToolbar(ready, busy, onConnect, onApps, onSettings, compact = true)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConnectionStrip(ready, busy, status, txFlash, Modifier.weight(1f), compact = true) { showStatus = true }
                        RemoteToolbar(ready, busy, onConnect, onApps, onSettings, compact = true)
                    }
                }
            }
        }
        return
    }

    RemotePage(contentPadding, modifier) { dialSize ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("KAUKOSÄÄDIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("Set the mood.", style = MaterialTheme.typography.headlineLarge)
            }
            deck()
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DevicePicker(devices, current, enabled = !busy, onSelect, onAddDevice, onManageDevices = onDevices, onGeneralSettings)
            ConnectionStrip(ready, busy, status, txFlash) { showStatus = true }
            RemoteToolbar(ready, busy, onConnect, onApps, onSettings)
        }
        body(dialSize)
    }
}

/** The TV remotes' safe viewport, with the header alongside the controls in landscape. */
@Composable
private fun RemoteViewport(
    contentPadding: PaddingValues,
    modifier: Modifier,
    body: @Composable (Dp) -> Unit,
    header: @Composable (landscape: Boolean) -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(contentPadding)
                .padding(12.dp),
        ) {
            val landscape = maxWidth > maxHeight
            val dialSize = minOf(maxWidth, maxHeight)
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(0.5f)) { header(true) }
                    Column(Modifier.weight(1f)) { body(dialSize) }
                }
            } else {
                Column(
                    Modifier.widthIn(max = 440.dp).fillMaxSize().align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    header(false)
                    Column(Modifier.weight(1f)) { body(dialSize) }
                }
            }
        }
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

/** A readable, centered control panel on wide screens; smaller screens scroll without shrinking. */
@Composable
private fun RemotePage(
    contentPadding: PaddingValues,
    modifier: Modifier,
    content: @Composable (dialSize: Dp) -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Column(
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(contentPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(20.dp)) {
                val dialSize = maxWidth.coerceAtMost(280.dp)
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    content(dialSize)
                }
            }
        }
    }
}

/** Readiness is text, not just color; the full diagnostic remains one tap away. */
@Composable
private fun ConnectionStrip(
    ready: Boolean,
    busy: Boolean,
    status: String,
    txFlash: () -> Float,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onShowStatus: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Show full status", onClick = onShowStatus)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(8.dp)) {
            drawCircle(if (ready) colors.primary else colors.onSurfaceVariant)
            drawCircle(colors.onSurface, alpha = txFlash().coerceIn(0f, 1f))
        }
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    busy -> "Working…"
                    ready -> "Ready"
                    else -> "Not connected"
                },
                style = MaterialTheme.typography.labelLarge,
            )
            if (!compact) Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!compact) Text("Details ↗", style = MaterialTheme.typography.labelMedium, color = colors.primary)
    }
}

/**
 * The row under every remote's device header: the kind's own actions on the left (Connect
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
    compact: Boolean = false,
) {
    Row(if (compact) Modifier else Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onConnect != null) {
            TextButton(onClick = onConnect, enabled = !busy) { Text(if (ready) "Reconnect TV" else "Connect TV") }
        }
        if (onApps != null) TextButton(onClick = onApps, enabled = !busy) { Text("Apps") }
        if (!compact) Spacer(Modifier.weight(1f))
        TextButton(onClick = onSettings, enabled = !busy) { Text(if (compact) "Settings" else "Device settings") }
    }
}

/** The line [kind]'s status dialog adds: LG's power is wake-only, and Apple TV's Back and Home take gestures (see [gestures]). */
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
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status)
                footerNote(device.kind).takeIf { it.isNotEmpty() }?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
