package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.companion.AppleTvApp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient

/**
 * The Super remote's app shortcuts, in one row at the top. [shortcuts] and [appleTv] are the
 * configured Apple TV's, resolved by [SuperRemoteScreen] from the Super remote's own bindings, so
 * the row never follows the device picker. A row that is not set up yet, or whose Apple TV is gone,
 * asks for reselection instead of asking any TV to open something.
 */
@Composable
internal fun SuperRemoteAppsRow(
    shortcuts: List<AppleTvApp>,
    appleTv: CompanionClient?,
    onSetup: () -> Unit,
) {
    Text("Apps", style = MaterialTheme.typography.titleMedium)
    when {
        appleTv == null -> {
            Text("No Apple TV chosen yet, so there is nothing to launch on.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onSetup) { Text("Choose Apple TV") }
        }
        shortcuts.isEmpty() -> {
            Text("No app shortcuts chosen yet. Pick them from the Apple TV's own app list.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onSetup) { Text("Choose apps") }
        }
        else ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                shortcuts.forEach { Text(it.name, style = MaterialTheme.typography.titleMedium) }
            }
    }
}
