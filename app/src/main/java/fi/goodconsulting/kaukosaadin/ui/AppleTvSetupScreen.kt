package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.companion.CompanionDiscovery
import kotlinx.coroutines.launch

/** Shown while the Apple TV displays its pairing PIN; cancelling ends that pairing attempt. */
@Composable
fun AppleTvPinDialog(client: CompanionClient) {
    val awaitingPin by client.awaitingPin.collectAsState()
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf("") }
    LaunchedEffect(awaitingPin) { pin = ""; pinError = "" }
    DisposableEffect(client) { onDispose { client.cancelPairing() } }
    if (awaitingPin) {
        AlertDialog(
            onDismissRequest = { client.cancelPairing() },
            title = { Text("Enter the Apple TV PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the four digits shown on the Apple TV. Expires after 90 seconds.")
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, label = { Text("Apple TV PIN") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    if (pinError.isNotEmpty()) Text(pinError)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val submitted = client.submitPin(pin)
                    pinError = if (submitted.ok) "" else submitted.message
                    if (submitted.ok) pin = ""
                }) { Text("Submit PIN") }
            },
            dismissButton = { TextButton(onClick = { client.cancelPairing() }) { Text("Cancel pairing") } },
        )
    }
}

@Composable
fun AppleTvSetupScreen(
    padding: PaddingValues,
    client: CompanionClient,
    discovery: CompanionDiscovery,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status by client.status.collectAsState()
    val paired by client.paired.collectAsState()
    BackHandler(onBack = onBack)
    var busy by remember { mutableStateOf(false) }
    var scanStatus by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf(emptyList<CompanionDiscovery.Device>()) }
    var confirmForget by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { block() } finally { busy = false } }
    }
    fun scan() = run {
        scanStatus = "Scanning…"
        devices = emptyList()
        scanStatus = try {
            devices = discovery.scan()
            if (devices.isEmpty()) "No Apple TVs found. Wake it with its own remote, check same Wi-Fi, then scan again."
            else "Found ${devices.size} device(s). Names are advertised, not proven; pairing with the PIN proves the TV."
        } catch (_: Exception) { "Scan failed. Check Wi-Fi/LAN access, then retry." }
    }
    LaunchedEffect(Unit) { if (!paired) scan() }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget Apple TV?") },
            text = { Text("This removes the saved Apple TV and its pairing. You'll need to pair it again with a new PIN.") },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; run { client.forget() } }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Apple TV setup", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("Done") }
        Text("Pairs with an Apple TV on a trusted home LAN using the PIN it shows. No wake or power control.")
        if (paired) Text("Paired: ${client.name}")
        Text(status.message)
        Button(enabled = !busy, onClick = ::scan) { Text("Find Apple TVs") }
        if (scanStatus.isNotEmpty()) Text(scanStatus)
        devices.forEach { device ->
            OutlinedButton(enabled = !busy, onClick = {
                run { if (client.pair(device).ok) onBack() }
            }) { Text("Pair ${device.name} · ${device.address.hostAddress}") }
        }
        Text("Each press connects, verifies the saved pairing and waits for the Apple TV's acknowledgment. Nothing is queued or replayed.")
        if (paired) TextButton(enabled = !busy, onClick = { confirmForget = true }) { Text("Forget Apple TV") }
        TextButton(onClick = onBack) { Text("Done") }
    }
}
