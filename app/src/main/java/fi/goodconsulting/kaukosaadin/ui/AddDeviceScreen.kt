package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceStore
import kotlinx.coroutines.launch

/**
 * One scan for every supported device, one section per registered integration: LG TVs and Apple
 * TVs side by side. Picking one pairs it with the PIN it shows and saves it; nothing pairs
 * automatically. Each section scans from the one Scan button and reports its own failures.
 */
@Composable
fun AddDeviceScreen(
    padding: PaddingValues,
    store: DeviceStore,
    onBack: () -> Unit,
    onAdded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var scans by remember { mutableIntStateOf(0) }
    var scanToken by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

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

    val host =
        SetupHost(
            store = store,
            scanToken = scanToken,
            busy = busy,
            message = message,
            onMessage = { message = it },
            run = ::run,
            onScanning = { active -> scans = maxOf(0, scans + if (active) 1 else -1) },
            onAdded = onAdded,
        )
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Add device", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("Done") }
        Text("Finds LG webOS TVs and Apple TVs on your Wi-Fi. Pick one to pair it with the PIN it shows.")
        Button(enabled = scans == 0, onClick = { scanToken++ }) { Text(if (scans > 0) "Scanning…" else "Scan again") }
        if (message.isNotEmpty()) Text(message)
        DeviceIntegrations.all.forEach { it.Setup(host) }
        Text(
            "Names are what devices advertise, not proof of identity; the PIN on the screen is what proves it.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onBack) { Text("Done") }
    }
}

/** One scan result: tapping an unsaved one pairs it. Shared by every integration's setup section. */
@Composable
internal fun FoundDevice(
    name: String,
    host: String,
    saved: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    OutlinedButton(enabled = enabled && !saved, onClick = onPick, modifier = Modifier.fillMaxWidth()) {
        Text(if (saved) "$name · $host · Saved" else "$name · $host")
    }
}

internal const val SAVE_FAILED = "Paired, but the device could not be saved. Try again."
