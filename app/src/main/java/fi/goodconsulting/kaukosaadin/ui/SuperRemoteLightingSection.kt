package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting

/**
 * The room or zone the Super remote's lighting presets command. [lighting] is the configured bridge
 * with the chosen grouped light and [client] its lighting client, resolved by [SuperRemoteScreen]
 * from the Super remote's own bindings. When the bridge was forgotten, the target was deleted or
 * nothing was chosen yet, the section asks for reselection and commands nothing.
 */
@Composable
internal fun SuperRemoteLightingSection(
    lighting: SuperRemoteLighting?,
    client: HueLighting?,
    onSetup: () -> Unit,
) {
    Text("Lighting", style = MaterialTheme.typography.titleMedium)
    if (lighting == null || client == null) {
        Text("No room or zone chosen yet on a saved Hue Bridge.", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onSetup) { Text("Choose room or zone") }
    } else {
        Text("${lighting.target.name} · ${lighting.bridge.name}", style = MaterialTheme.typography.bodyMedium)
    }
}
