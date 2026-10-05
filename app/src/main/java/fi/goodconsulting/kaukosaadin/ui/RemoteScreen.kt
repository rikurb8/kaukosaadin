package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp
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
 * Only actual saved devices appear; controls require verified registration (LG) or
 * a saved pairing (Apple TV). [onConnect] is null for targets that verify per press.
 */
@Composable
internal fun RemoteScreen(
    contentPadding: PaddingValues,
    target: Target,
    layout: AppLayout,
    tvName: String,
    ready: Boolean,
    busy: Boolean,
    wakeEnabled: Boolean,
    status: String,
    onTarget: (Target) -> Unit,
    onConnect: (() -> Unit)?,
    onSettings: () -> Unit,
    onGeneralSettings: () -> Unit,
    onWake: () -> Unit,
    onKey: (RemoteKey, PressAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var txCount by remember { mutableIntStateOf(0) }
    val navigationEnabled = ready && !busy

    fun send(key: RemoteKey, action: PressAction = PressAction.Tap) {
        txCount++
        onKey(key, action)
    }

    val txFlash = remember { Animatable(0f) }
    LaunchedEffect(txCount) {
        if (txCount > 0) {
            txFlash.snapTo(1f)
            txFlash.animateTo(0f, tween(durationMillis = 380, easing = LinearOutSlowInEasing))
        }
    }

    // ponytail: in-memory per remote session; persist to prefs if debugging needs survive restarts.
    val log = remember { mutableStateListOf<LogLine>() }
    LaunchedEffect(status) { log += LogLine(LocalTime.now().format(LogClock), status) }

    if (layout == AppLayout.Debug) {
        DebugRemoteScreen(
            contentPadding = contentPadding,
            target = target,
            tvName = tvName,
            ready = ready,
            busy = busy,
            wakeEnabled = wakeEnabled,
            txCount = txCount,
            log = log,
            navigationEnabled = navigationEnabled,
            onTarget = onTarget,
            onConnect = onConnect,
            onSettings = onSettings,
            onGeneralSettings = onGeneralSettings,
            onWake = { txCount++; onWake() },
            onPress = { key, action -> send(key, action) },
        )
        return
    }

    var showStatus by remember(target) { mutableStateOf(false) }
    if (showStatus) {
        AlertDialog(
            onDismissRequest = { showStatus = false },
            title = { Text("${target.label} status") },
            text = { Text(status) },
            confirmButton = { TextButton(onClick = { showStatus = false }) { Text("Close") } },
        )
    }

    val colors = MaterialTheme.colorScheme
    val casingBrush = Brush.verticalGradient(listOf(colors.surface.tint(0.05f), colors.surface.shade(0.06f)))
    val casingShape = RoundedCornerShape(32.dp)
    val screwColor = colors.outline
    BoxWithConstraints(modifier.fillMaxSize()) {
        // On a phone the phone *is* the remote: the casing fills the screen,
        // behind the system bars too. Wide screens keep a phone-sized casing
        // on the desk backdrop instead of stretching the keys across it.
        val framed = maxWidth >= FramedMinWidth
        val casingWidth = if (framed) FramedMaxWidth else maxWidth
        val dialSize = (casingWidth * 0.62f).coerceIn(MinDialSize, MaxDialSize)
        // Captured for fitToHeight: the scroll container's ColumnScope shadows the
        // BoxWithConstraints scope inside the content lambda.
        val viewportHeight = maxHeight
        val visibleHeight = viewportHeight - contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding()
        val casing = if (framed) {
            Modifier
                .width(FramedMaxWidth)
                .shadow(elevation = 16.dp, shape = casingShape)
                .clip(casingShape)
                .background(casingBrush)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), colors.outline)),
                    shape = casingShape,
                )
                .drawBehind {
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
            modifier = Modifier
                .fillMaxSize()
                .then(if (framed) Modifier else Modifier.background(casingBrush))
                // Fitting keeps the whole remote on screen while it can; past the
                // scale floor the rest scrolls, so the system font setting keeps
                // growing the text instead of being scaled away.
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fitToHeight(viewportHeight)
                    .padding(contentPadding)
                    .then(if (framed) Modifier.padding(horizontal = 16.dp, vertical = 8.dp) else Modifier)
                    .then(casing)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = if (framed) Arrangement.spacedBy(18.dp) else spacedEvenly(atLeast = 18.dp),
            ) {
            PowerDeck(
                ready = ready,
                wakeEnabled = wakeEnabled && !busy,
                txFlash = { txFlash.value },
                onWake = {
                    txCount++
                    onWake()
                },
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Target.entries.forEachIndexed { index, entry ->
                        SegmentedButton(
                            selected = entry == target,
                            onClick = { onTarget(entry) },
                            enabled = !busy,
                            shape = SegmentedButtonDefaults.itemShape(index, Target.entries.size),
                        ) { Text(entry.label) }
                    }
                }
                VfdDisplay(
                    target = target,
                    tvName = tvName,
                    ready = ready,
                    status = status.uppercase(Locale.US),
                    txFlash = { txFlash.value },
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    // Stable footprint; the full diagnostic remains available on tap.
                    minLines = 3,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Show full status") { showStatus = true },
                )
                Row {
                    if (onConnect != null) {
                        TextButton(onClick = onConnect, enabled = !busy) {
                            Text(if (ready) "Reconnect TV" else "Connect TV")
                        }
                        TextButton(onClick = onSettings, enabled = !busy) { Text("TV settings") }
                    } else {
                        TextButton(onClick = onSettings, enabled = !busy) { Text("Apple TV settings") }
                    }
                }
                TextButton(onClick = onGeneralSettings) { Text("General settings") }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RemoteKeys(
                    dialSize = dialSize,
                    target = target,
                    navigationEnabled = navigationEnabled,
                    onPress = { key, action -> send(key, action) },
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (target == Target.Lg) "Wake only · TV power is not monitored" else "No Apple TV wake · power is not monitored",
                    style = MaterialTheme.typography.bodySmall,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                SpeakerGrille()
                    EngravedLabel("MODEL KS-01 · UNIVERSAL")
                }
            }
        }
    }
}
