package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.hue.HueClient
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting

/**
 * The Super remote: the configured Apple TV's app shortcuts on top, its controls centrally, and the
 * configured room's or zone's lighting below, in one scrolling screen so accessible text sizes grow
 * the content instead of clipping it. It drives [bindings] and never the picker's selected device,
 * so picking or forgetting a device elsewhere cannot redirect one of its actions.
 *
 * The three sections own their own clients, readiness and failures; this screen resolves the
 * bindings against [devices], builds each section's client once for the section's own device, and
 * routes to setup, to the saved devices and to general settings without touching the
 * bindings.
 */
@Composable
internal fun SuperRemoteScreen(
    padding: PaddingValues,
    devices: List<SavedDevice>,
    bindings: SuperRemoteBindings,
    onSetup: () -> Unit,
    onDevices: () -> Unit,
    onGeneralSettings: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val targets = bindings.resolve(devices)
    val appleTv =
        remember(targets.appleTv?.id) { targets.appleTv?.let { CompanionClient(context, it.id) } }
    val lighting =
        remember(targets.lighting?.bridge?.id) {
            targets.lighting?.bridge?.let { HueLighting.of(HueClient(context, it.id), scope) }
        }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Super remote", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onSetup) { Text("Set up devices") }
            TextButton(onClick = onDevices) { Text("Devices") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onGeneralSettings) { Text("General settings") }
        }
        SuperRemoteAppsRow(shortcuts = bindings.shortcuts, appleTv = appleTv, onSetup = onSetup)
        SuperRemoteAppleTvSection(device = targets.appleTv, client = appleTv, onSetup = onSetup)
        SuperRemoteLightingSection(lighting = targets.lighting, client = lighting, onSetup = onSetup)
    }
}
