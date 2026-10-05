package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import kotlinx.coroutines.launch

/**
 * Apps the Apple TV offers to launch. The list is the TV's own; tapping one asks the TV to open
 * it, which lands on that app's home screen. The remote's session stays open while this is shown.
 */
@Composable
fun AppleTvAppsScreen(
    padding: PaddingValues,
    client: CompanionClient,
    onBack: () -> Unit,
) {
    val apps by client.apps.collectAsState()
    val status by client.status.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    // Refresh on open: the TV reports this only while awake, and installed apps change.
    LaunchedEffect(Unit) { run { client.appList() } }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Apple TV apps", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("Back to remote") }
        Text(
            "Names and bundles come from the Apple TV; it lists only apps it offers to launch. " +
                "Launching opens the app itself, not specific content.",
        )
        Button(enabled = !busy, onClick = { run { client.appList() } }) { Text("Refresh") }
        Text(status.message)
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(apps, key = { it.bundleId }) { app ->
                TextButton(enabled = !busy, onClick = { run { client.launchApp(app) } }) { Text(app.name) }
            }
        }
    }
}
