package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import fi.goodconsulting.kaukosaadin.device.LgClient
import fi.goodconsulting.kaukosaadin.device.LgProtocol
import kotlinx.coroutines.launch

/** Shared by setup and the main remote's saved-TV reconnect button. */
@Composable
fun LgPinDialog(client: LgClient) {
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
}

@Composable
fun LgConnectionScreen(padding: PaddingValues, client: LgClient, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status by client.status.collectAsState()
    val devices by client.devices.collectAsState()
    val ready by client.ready.collectAsState()
    BackHandler(onBack = onBack)
    var manualAddress by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(client.host) }
    var name by remember { mutableStateOf(client.name) }
    var confirmRemove by remember { mutableStateOf(false) }
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
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove TV?") },
            text = { Text("This removes the saved TV, pairing and wake settings. You'll need to add and pair it again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    run { client.remove().also { if (it.ok) onBack() } }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("TV setup", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("Done") }
        Text("Supports LG webOS TVs on a trusted home LAN. Wake only — no power-off.")
        Button(enabled = !busy, onClick = { run { client.discover() } }) { Text("Find LG TVs") }
        Text((if (busy) status else result ?: status).message)
        devices.forEach { tv ->
            OutlinedButton(enabled = !busy, onClick = {
                if (host != tv.ip) {
                    host = tv.ip; mac = ""; broadcast = ""; fingerprint = ""; approved = false
                }
                name = if (tv.name == tv.ip) "LG TV" else tv.name
            }) { Text("${tv.name} · ${tv.ip}") }
        }
        Text("Selected TV: ${host.ifEmpty { "none" }}")
        Text("Sleeping TVs may not reply. Saved setup is retained; discovery never pairs automatically.")
        TextButton(enabled = !busy, onClick = { manualAddress = !manualAddress }) { Text("Manual address fallback") }
        if (manualAddress) {
            OutlinedTextField(host, {
                host = it; name = "LG TV"; mac = ""; broadcast = ""; approved = false; fingerprint = ""
            }, label = { Text("TV IPv4 address") }, enabled = !busy)
        }
        if (host.isNotEmpty()) {
            OutlinedTextField(name, { name = it }, label = { Text("TV name") }, enabled = !busy, singleLine = true)
            Text("Discovery provides a display name, not proof of identity. Rename it if you like.")
            if (host == client.host) {
                OutlinedButton(enabled = !busy, onClick = { run { client.saveName(host, name) } }) { Text("Save TV name") }
            }
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
        Button(enabled = !busy && approved, onClick = { run { client.save(host, fingerprint, name) } }) {
            Text("Save approved setup")
        }
        Button(enabled = !busy && setupSaved, onClick = {
            run {
                val named = client.saveName(host, name)
                if (named.ok) client.connect().also { if (it.ok) onBack() } else named
            }
        }) { Text("Connect / pair LG") }
        Text((if (busy) status else result ?: status).message)
        if (!setupSaved) Text("Inspect, approve and save this TV's certificate to enable Connect / pair LG.")
        if (ready && setupSaved) {
            Button(enabled = !busy, onClick = onBack) { Text("Use LG remote") }
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
        if (client.host.isNotEmpty()) {
            TextButton(enabled = !busy, onClick = { confirmRemove = true }) { Text("Remove TV") }
        }
        TextButton(onClick = onBack) { Text("Done") }
    }
}
