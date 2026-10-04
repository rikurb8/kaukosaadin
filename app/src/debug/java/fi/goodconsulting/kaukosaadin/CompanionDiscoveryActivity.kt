package fi.goodconsulting.kaukosaadin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Debug-only, explicitly triggered LAN scan. No TCP connections, saved data or TV commands. */
class CompanionDiscoveryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val discovery = remember { CompanionDiscovery(applicationContext) }
            val scope = rememberCoroutineScope()
            var job by remember { mutableStateOf<Job?>(null) }
            var busy by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("Not scanned") }
            var devices by remember { mutableStateOf(emptyList<CompanionDiscovery.Device>()) }
            MaterialTheme {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Companion discovery", style = MaterialTheme.typography.headlineSmall)
                        Text("Read-only LAN scan. Does not pair, wake or control a TV, or change saved LG setup.")
                        Button(enabled = !busy, onClick = {
                            busy = true
                            devices = emptyList()
                            status = "Scanning Companion services…"
                            job = scope.launch {
                                try {
                                    devices = discovery.scan()
                                    status = "Scan complete: ${devices.size} Companion service(s)"
                                } catch (e: CancellationException) {
                                    status = "Scan cancelled"
                                    throw e
                                } catch (_: Exception) {
                                    status = "Discovery failed. Check Wi-Fi/LAN access and router isolation, then retry."
                                } finally { busy = false }
                            }
                        }) { Text("Scan Companion services") }
                        Button(enabled = busy, onClick = { job?.cancel() }) { Text("Cancel scan") }
                        Text(status)
                        devices.forEach { device ->
                            Text(device.name)
                            Text("${device.address.hostAddress} · port ${device.port}")
                        }
                        Text("Companion advertisements may include non-TV devices. Discovery proves no identity or pairing.")
                        Text("No results? Turn Apple TV on and check the same LAN. A sleeping device may stop advertising; saved setup is unchanged.")
                    }
                }
            }
        }
    }
}
