package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fi.goodconsulting.kaukosaadin.device.hue.HueCommandTarget
import fi.goodconsulting.kaukosaadin.device.hue.HueConnectionState
import fi.goodconsulting.kaukosaadin.device.hue.HueGroupedLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Super remote's lighting: Bright, Dim and Off for the configured room's or zone's grouped light,
 * above the state the bridge reports. [lighting] is the configured bridge with its chosen grouped
 * light and [client] its lighting client, resolved by [SuperRemoteScreen] from the Super remote's own
 * bindings. With no chosen room or zone, a forgotten bridge or a bridge with no client, the section
 * asks for reselection and commands nothing: it never falls back to another bridge, room, zone or
 * light.
 *
 * The section owns its own [LightingController] and its own readiness and failure, apart from the
 * Apple TV section's session, so a bridge failure never blocks the TV controls and vice versa.
 */
@Composable
internal fun SuperRemoteLightingSection(
    lighting: SuperRemoteLighting?,
    client: HueLighting?,
    onSetup: () -> Unit,
) {
    Text("Lighting", style = MaterialTheme.typography.titleMedium)
    val target = presetTarget(lighting, client)
    if (lighting == null || client == null || target == null) {
        Text("No room or zone chosen yet on a saved Hue Bridge.", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onSetup) { Text("Choose room or zone") }
        return
    }
    SuperRemoteLightingPresets(lighting, target, client, onSetup)
}

/**
 * The stateful part: holds the bridge's live subscription while the section is visible and renders
 * the presets and the state the bridge reports. The controller's existing live lifetime, read and
 * event merging are reused, so the same rules as the lighting screen apply here.
 */
@Composable
private fun SuperRemoteLightingPresets(
    lighting: SuperRemoteLighting,
    target: HueCommandTarget.Group,
    client: HueLighting,
    onSetup: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(client) { LightingController(client) }
    val activity = LocalActivity.current
    val state by controller.state.collectAsState()
    val connection by controller.connection.collectAsState()

    // Opening the section connects the live subscription and reads the bridge; leaving or
    // backgrounding releases it, and coming back repeats both. Nothing here sends a command.
    LaunchedEffect(activity, controller) {
        if (activity is LifecycleOwner) {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { controller.live() }
        }
    }

    SuperRemoteLightingFailure(failureMessage(connection, state))
    Text("${lighting.target.name} · ${lighting.bridge.name}", style = MaterialTheme.typography.bodyMedium)
    if (state.targetMissing(target)) {
        Text(
            "The bridge no longer reports ${lighting.target.name}. Choose the room or zone again.",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onSetup) { Text("Choose room or zone") }
        return
    }
    Text(reportedState(state.groupedLights.firstOrNull { it.id == target.id }), style = MaterialTheme.typography.bodyMedium)
    // Disabled while the live subscription is still opening or this target's command is in flight,
    // so a repeated tap is refused rather than becoming a second command.
    val unavailable = connection is HueConnectionState.Connecting || target.id in state.busyTargets
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LightingPreset.entries.forEach { preset ->
            Button(
                onClick = { scope.launch { controller.applyPreset(target, preset) } },
                enabled = !unavailable,
            ) { Text(preset.label) }
        }
    }
}

/** The failure to show for this section alone: a failed bridge read, command or live connection. */
@Composable
private fun SuperRemoteLightingFailure(message: String?) {
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

/** What the bridge reports for the configured target, or that the read is still running. */
private fun reportedState(groupedLight: HueGroupedLight?): String =
    when {
        groupedLight == null -> "Reading the room's or zone's state from the bridge…"
        !groupedLight.on -> "Off"
        groupedLight.brightness == null -> "On"
        else -> "On at ${groupedLight.brightness.roundToInt()}%"
    }

/**
 * The grouped light the presets command, or null when there is nothing to command: no chosen room or
 * zone, a forgotten bridge, or a bridge with no client. It always names exactly the configured
 * target, so a preset can never be redirected to another bridge, room, zone or light.
 */
internal fun presetTarget(
    lighting: SuperRemoteLighting?,
    client: HueLighting?,
): HueCommandTarget.Group? = if (lighting == null || client == null) null else HueCommandTarget.Group(lighting.target.id)

/**
 * Whether a completed read no longer reports [target]: the chosen room, zone or grouped light was
 * deleted on the bridge, so the section asks for reselection instead of commanding the missing target.
 */
internal fun LightingState.targetMissing(target: HueCommandTarget.Group): Boolean =
    !loading && failure == null && groupedLights.none { it.id == target.id }
