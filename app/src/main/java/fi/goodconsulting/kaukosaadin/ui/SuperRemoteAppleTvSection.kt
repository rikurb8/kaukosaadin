package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient

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
    } else {
        Text("${device.name} · ${device.host}", style = MaterialTheme.typography.bodyMedium)
    }
}
