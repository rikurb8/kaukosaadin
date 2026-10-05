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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.companion.PressAction
import java.time.format.DateTimeFormatter

/** One status change with the wall-clock time it appeared. */
internal data class LogLine(val time: String, val text: String)

internal val LogClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** Newest-first cap: bounds the panel without a second, nested scroll container. */
internal const val DebugLogLimit = 40

/**
 * Developer layout: flat panels and a live status log instead of the decorative
 * casing. Drives the same keys and commands as the standard layout.
 */
@Composable
internal fun DebugRemoteScreen(
    contentPadding: PaddingValues,
    target: Target,
    tvName: String,
    ready: Boolean,
    busy: Boolean,
    wakeEnabled: Boolean,
    txCount: Int,
    log: List<LogLine>,
    navigationEnabled: Boolean,
    onTarget: (Target) -> Unit,
    onConnect: (() -> Unit)?,
    onSettings: () -> Unit,
    onGeneralSettings: () -> Unit,
    onWake: () -> Unit,
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
            "DEBUG LAYOUT · ${target.platform}",
            style = monospace(MaterialTheme.typography.labelMedium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DebugPanel(listOf(
            "target" to target.label,
            "device" to tvName,
            "ready" to ready.toString(),
            "busy" to busy.toString(),
            "commands" to txCount.toString(),
        ))
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onConnect != null) {
                TextButton(onClick = onConnect, enabled = !busy) { Text(if (ready) "Reconnect TV" else "Connect TV") }
                TextButton(onClick = onSettings, enabled = !busy) { Text("TV settings") }
            } else {
                TextButton(onClick = onSettings, enabled = !busy) { Text("Apple TV settings") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onGeneralSettings) { Text("General settings") }
            TextButton(onClick = onWake, enabled = wakeEnabled && !busy) { Text("Wake") }
        }
        DebugPanel(log.takeLast(DebugLogLimit).reversed().map { it.time to it.text })
        RemoteKeys(
            dialSize = MinDialSize,
            target = target,
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
