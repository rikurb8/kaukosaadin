package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import fi.goodconsulting.kaukosaadin.device.LgClient
import fi.goodconsulting.kaukosaadin.device.LgProtocol
import kotlinx.coroutines.launch

/** Temporary client exerciser for GOO-26; GOO-29 owns the final remote integration. */
@Composable
fun LgTestScreen(padding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val client = remember { LgClient(context) }
    val scope = rememberCoroutineScope()
    val status by client.status.collectAsState()
    val devices by client.devices.collectAsState()
    val awaitingPin by client.awaitingPin.collectAsState()
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf("") }
    LaunchedEffect(awaitingPin) { pin = ""; pinError = "" }
    DisposableEffect(client) { onDispose { client.cancelPairing() } }
    if (awaitingPin) {
        AlertDialog(
            onDismissRequest = { client.cancelPairing() },
            title = { Text("Enter the TV PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the code shown on the TV. No physical remote approval is requested. Expires after 90 seconds.")
                    OutlinedTextField(pin, { pin = it }, label = { Text("TV PIN") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation())
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
    var manualAddress by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(client.host) }
    var mac by remember { mutableStateOf(client.mac) }
    var broadcast by remember { mutableStateOf(client.broadcast) }
    var fingerprint by remember { mutableStateOf(client.fingerprint) }
    var approved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LgClient.Result?>(null) }
    fun run(block: suspend () -> LgClient.Result) {
        if (busy) return
        busy = true
        scope.launch {
            try { result = block() } finally { busy = false }
        }
    }
    LaunchedEffect(client) {
        run { client.discover() }
    }
    val setupSaved = host.isNotEmpty() && host == client.host &&
        fingerprint.isNotEmpty() && fingerprint == client.fingerprint
    val wakeSaved = setupSaved && mac.isNotEmpty() && broadcast.isNotEmpty() &&
        mac == client.mac && broadcast == client.broadcast
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("LG G3 client test", style = MaterialTheme.typography.titleLarge)
        Text("Wake only — no power-off. Apple TV is not involved. Use a trusted home LAN.")
        Button(enabled = !busy, onClick = { run { client.discover() } }) { Text("Find LG TVs") }
        Text((if (busy) status else result ?: status).message)
        devices.forEach { tv ->
            OutlinedButton(enabled = !busy, onClick = {
                if (host != tv.ip) {
                    host = tv.ip; mac = ""; broadcast = ""; fingerprint = ""; approved = false
                }
            }) { Text("${tv.name} · ${tv.ip}") }
        }
        Text("Selected TV: ${host.ifEmpty { "none" }}")
        Text("Sleeping TVs may not reply. Saved setup is retained; discovery never pairs automatically.")
        TextButton(enabled = !busy, onClick = { manualAddress = !manualAddress }) { Text("Manual address fallback") }
        if (manualAddress) {
            OutlinedTextField(host, {
                host = it; mac = ""; broadcast = ""; approved = false; fingerprint = ""
            }, label = { Text("TV IPv4 address") }, enabled = !busy)
        }
        Text("Pairing: inspect certificate → approve → save → Connect → enter the TV PIN. No MAC or broadcast needed.")
        Button(enabled = !busy && host.isNotEmpty(), onClick = {
            run {
                val inspected = client.inspect(host)
                approved = false
                if (inspected != null) fingerprint = inspected
                client.status.value
            }
        }) { Text("Inspect TV certificate") }
        Text("Certificate SHA-256: ${fingerprint.ifEmpty { "not inspected" }}")
        Text("First trust cannot prove TV identity. Verify the address/certificate using a trusted router or independent client before approving. Changed certificates require explicit approval again.")
        Row {
            Checkbox(approved, { approved = it }, enabled = !busy && fingerprint.isNotEmpty())
            Text("I verified this TV certificate", Modifier.padding(top = 12.dp))
        }
        Button(enabled = !busy && approved, onClick = { run { client.save(host, fingerprint) } }) {
            Text("Save approved setup")
        }
        Button(enabled = !busy && setupSaved, onClick = { run { client.connect() } }) { Text("Connect / pair LG") }
        Text((if (busy) status else result ?: status).message)
        if (!setupSaved) Text("Inspect, approve and save this TV's certificate to enable Connect / pair LG.")
        LgProtocol.Action.entries.filter { it != LgProtocol.Action.Wake }.forEach { action ->
            Button(enabled = !busy && setupSaved, onClick = { run { client.send(action) } }) { Text("LG ${action.name}") }
        }
        Text("Wake settings (optional for pairing/navigation)", style = MaterialTheme.typography.titleMedium)
        Text("SSDP does not provide a wake MAC. Enter the TV's active network MAC and subnet broadcast only for wake.")
        OutlinedTextField(mac, { mac = it }, label = { Text("TV network MAC") }, enabled = !busy)
        OutlinedTextField(broadcast, { broadcast = it }, label = { Text("Subnet broadcast IPv4") }, enabled = !busy)
        Button(enabled = !busy && setupSaved, onClick = { run { client.saveWake(host, mac, broadcast) } }) {
            Text("Save wake settings")
        }
        Text((if (busy) status else result ?: status).message)
        Button(enabled = !busy && wakeSaved, onClick = { run { client.send(LgProtocol.Action.Wake) } }) { Text("LG Wake") }
        if (!wakeSaved) Text("Save valid MAC and broadcast settings to enable LG Wake; Connect is independent.")
        Button(enabled = !busy, onClick = {
            run {
                val forgotten = client.forget()
                fingerprint = ""; approved = false
                forgotten
            }
        }) { Text("Forget LG pairing and certificate") }
        TextButton(onClick = onBack, enabled = !busy) { Text("Back to simulated remote") }
    }
}
