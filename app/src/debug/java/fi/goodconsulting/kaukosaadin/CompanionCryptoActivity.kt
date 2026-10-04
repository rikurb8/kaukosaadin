package fi.goodconsulting.kaukosaadin

import android.os.Build
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
import fi.goodconsulting.kaukosaadin.device.companion.CompanionCryptoCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only gate. No discovery, network, real credentials or TV commands. */
class CompanionCryptoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var busy by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("Not run on this device") }
            var checks by remember { mutableStateOf(emptyList<String>()) }
            val scope = rememberCoroutineScope()
            MaterialTheme {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Companion crypto gate", style = MaterialTheme.typography.headlineSmall)
                        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                        Text("Android build: ${Build.DISPLAY}")
                        Text("Bouncy Castle 1.86 lightweight API; no global provider changes")
                        Text("pyatv ${CompanionCryptoCheck.REVISION.take(12)}")
                        Text("Synthetic vectors only. Does not pair, wake or control any TV.")
                        Button(enabled = !busy, onClick = {
                            busy = true
                            checks = emptyList()
                            status = "Running crypto checks…"
                            scope.launch {
                                try {
                                    checks = withContext(Dispatchers.Default) {
                                        val vectors = assets.open("companion-crypto-vectors.json").bufferedReader().use { it.readText() }
                                        CompanionCryptoCheck.run(vectors)
                                    }
                                    status = "Companion crypto: 6/6 PASS"
                                } catch (e: CancellationException) { throw e
                                } catch (_: Exception) {
                                    status = "Companion crypto: FAIL. Stop; investigate compatibility before pairing."
                                } finally { busy = false }
                            }
                        }) { Text("Run crypto checks") }
                        Text(status)
                        checks.forEach { Text(it) }
                        Text("A pass proves these crypto formats on this device, not Apple TV interoperability.")
                    }
                }
            }
        }
    }
}
