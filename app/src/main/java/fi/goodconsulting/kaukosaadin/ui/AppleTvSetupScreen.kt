package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.companion.CompanionDiscovery
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shown while the Apple TV displays its pairing PIN; cancelling ends that pairing attempt. */
@Composable
fun AppleTvPinDialog(client: CompanionClient) {
    val awaitingPin by client.awaitingPin.collectAsState()
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf("") }
    LaunchedEffect(awaitingPin) {
        pin = ""
        pinError = ""
    }
    DisposableEffect(client) { onDispose { client.cancelPairing() } }
    if (awaitingPin) {
        AlertDialog(
            onDismissRequest = { client.cancelPairing() },
            title = { Text("Enter the Apple TV PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the four digits shown on the Apple TV. Expires after 90 seconds.")
                    OutlinedTextField(
                        pin,
                        { pin = it.filter(Char::isDigit).take(4) },
                        label = { Text("Apple TV PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
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

/**
 * Appears while the Apple TV's on-screen keyboard is focused (Companion RTI push) and mirrors
 * typed text to it. Debounced: each edit sends one clear + insert, so backspace and paste work too.
 */
@Composable
fun AppleTvKeyboardDialog(client: CompanionClient) {
    val keyboard by client.keyboard.collectAsState()
    val focused = keyboard?.takeIf { it.focused }
    var hidden by remember(focused) { mutableStateOf(false) }
    if (focused == null || hidden) return
    val focusRequester = remember { FocusRequester() }
    val softKeyboard = LocalSoftwareKeyboardController.current
    var text by remember(focused) { mutableStateOf(focused.text) }
    var sent by remember(focused) { mutableStateOf(focused.text) }
    LaunchedEffect(focused) {
        focusRequester.requestFocus()
        softKeyboard?.show()
    }
    LaunchedEffect(text) {
        if (text == sent) return@LaunchedEffect
        delay(250)
        sent = text
        client.sendText(text)
    }
    AlertDialog(
        onDismissRequest = { hidden = true },
        title = { Text("Apple TV keyboard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type here; the text appears in the Apple TV's text field.")
                OutlinedTextField(
                    text,
                    { text = it },
                    label = { Text("Text") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        },
        confirmButton = { TextButton(onClick = { hidden = true }) { Text("Hide") } },
    )
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
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    fun scan() =
        run {
            scanStatus = "Scanning…"
            devices = emptyList()
            scanStatus =
                try {
                    devices = discovery.scan()
                    scanSummary(devices)
                } catch (_: Exception) {
                    "Scan failed. Check Wi-Fi/LAN access, then retry."
                }
        }
    LaunchedEffect(Unit) { if (!paired) scan() }
    if (confirmForget) {
        ForgetAppleTvDialog(
            onDismiss = { confirmForget = false },
            onConfirm = {
                confirmForget = false
                run { client.forget() }
            },
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Apple TV setup", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("Done") }
        Text("Pairs with an Apple TV on a trusted home LAN using the PIN it shows. No wake or power control.")
        if (paired) Text("Paired: ${client.name}")
        Text(status.message)
        Button(enabled = !busy, onClick = ::scan) { Text("Find Apple TVs") }
        if (scanStatus.isNotEmpty()) Text(scanStatus)
        AppleTvCandidates(devices, enabled = !busy) { device -> run { if (client.pair(device).ok) onBack() } }
        Text("Each press connects, verifies the saved pairing and waits for the Apple TV's acknowledgment. Nothing is queued or replayed.")
        if (paired) TextButton(enabled = !busy, onClick = { confirmForget = true }) { Text("Forget Apple TV") }
        TextButton(onClick = onBack) { Text("Done") }
    }
}

/** Names come from unauthenticated advertisements; the PIN step is what proves the TV. */
private fun scanSummary(devices: List<CompanionDiscovery.Device>) =
    if (devices.isEmpty()) {
        "No Apple TVs found. Wake it with its own remote, check same Wi-Fi, then scan again."
    } else {
        "Found ${devices.size} device(s). Names are advertised, not proven; pairing with the PIN proves the TV."
    }

@Composable
private fun ForgetAppleTvDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forget Apple TV?") },
        text = { Text("This removes the saved Apple TV and its pairing. You'll need to pair it again with a new PIN.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Forget") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AppleTvCandidates(
    devices: List<CompanionDiscovery.Device>,
    enabled: Boolean,
    onPick: (CompanionDiscovery.Device) -> Unit,
) {
    devices.forEach { device ->
        OutlinedButton(enabled = enabled, onClick = { onPick(device) }) {
            Text("Pair ${device.name} · ${device.address.hostAddress}")
        }
    }
}
