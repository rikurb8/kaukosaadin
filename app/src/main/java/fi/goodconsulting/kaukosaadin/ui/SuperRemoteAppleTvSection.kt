package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.companion.HidCommand
import fi.goodconsulting.kaukosaadin.device.companion.PressAction

/**
 * The Apple TV the Super remote drives. [device] and [client] are the configured saved Apple TV and
 * its client, resolved by [SuperRemoteScreen] from the Super remote's own bindings; the picker's
 * selection never reaches this section. When the configured Apple TV was forgotten or is no longer
 * an Apple TV, the section asks for reselection and drives nothing.
 */
@Composable
internal fun SuperRemoteAppleTvSection(
    device: SavedDevice?,
    client: CompanionClient?,
    onSetup: () -> Unit,
) {
    Text("Apple TV", style = MaterialTheme.typography.titleMedium)
    if (device == null || client == null) {
        Text("The chosen Apple TV is not saved on this phone any more.", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onSetup) { Text("Choose Apple TV") }
        return
    }
    // AppleTvSessionHost owns the RESUMED-scoped connect/disconnect and the keyboard dialog; these
    // controls only render its state and dispatch one press at a time through it.
    AppleTvSessionHost(client) { session ->
        AppleTvControls(
            device = device,
            session = session,
            onPress = { key, action ->
                dispatchAppleTvPress(session, { command, press -> client.press(command, press) }, key, action)
            },
            onReconnect = { dispatchAppleTvReconnect(session) { client.connect() } },
        )
    }
}

/**
 * One press on the Super remote's Apple TV controls: hands [key]'s Companion command and [action] to
 * [send], the client built for the configured Apple TV, through [session]'s single-in-flight guard,
 * so a tap while one is in flight is refused, never queued. There is no [session] for a forgotten
 * Apple TV, and then nothing is sent: a press never falls through to the picker's device or another
 * saved one.
 */
internal fun dispatchAppleTvPress(
    session: AppleTvSession?,
    send: suspend (HidCommand, PressAction) -> Unit,
    key: RemoteKey,
    action: PressAction,
) {
    if (session == null) return
    session.run { send(key.hid, action) }
}

/** True while the Apple TV can take a press: paired, and neither connecting nor already sending. */
internal fun appleTvControlsEnabled(
    paired: Boolean,
    connecting: Boolean,
    busy: Boolean,
) = paired && !connecting && !busy

/**
 * Reconnect for the Super remote's Apple TV controls: asks [connect] to restore the verified link,
 * through [session]'s single-in-flight guard, and does nothing else. It restores readiness only, so
 * a reconnect never repeats the press that failed — a new press is a new operator action.
 */
internal fun dispatchAppleTvReconnect(
    session: AppleTvSession,
    connect: suspend () -> Unit,
) = session.run { connect() }

/**
 * The configured Apple TV's name, its live readiness and the shared keypad. The keys grey out while
 * the TV cannot take a press and the readiness line says the same in words, so readiness never
 * depends on colour; the status line carries the failure and what to do about it. Reconnect restores
 * readiness only, is offered once the TV is paired, and is disabled while connecting or sending.
 */
@Composable
private fun AppleTvControls(
    device: SavedDevice,
    session: AppleTvSession,
    onPress: (RemoteKey, PressAction) -> Unit,
    onReconnect: () -> Unit,
) {
    val status by session.status.collectAsState()
    val paired by session.paired.collectAsState()
    val connecting by session.connecting.collectAsState()
    val busy by session.busy.collectAsState()
    val enabled = appleTvControlsEnabled(paired, connecting, busy)
    val readiness =
        when {
            connecting -> "Connecting to ${device.name}…"
            busy -> "Sending…"
            paired -> "Ready"
            else -> "Not paired on this phone."
        }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${device.name} · ${device.host}", style = MaterialTheme.typography.bodyMedium)
        Text(readiness, style = MaterialTheme.typography.bodySmall)
        Text(
            status.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RemoteKeys(
            dialSize = MinDialSize,
            kind = DeviceKind.AppleTv,
            navigationEnabled = enabled,
            onPress = onPress,
        )
        if (paired) TextButton(onClick = onReconnect, enabled = enabled) { Text("Reconnect") }
    }
}
