package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.LgClient

/** Shared by pairing and the remote's Connect button, which re-pairs if the TV dropped its key. */
@Composable
fun LgPinDialog(client: LgClient) {
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
            title = { Text("Enter the TV PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the code shown on the TV. No physical remote approval is requested. Expires after 90 seconds.")
                    OutlinedTextField(
                        pin,
                        { pin = it },
                        label = { Text("TV PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
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
 * The one trust decision in LG pairing: the certificate the TV presented, which later connections are
 * pinned to. First trust cannot prove identity, so the user is told what to check before trusting.
 */
@Composable
fun LgTrustDialog(
    name: String,
    host: String,
    fingerprint: String,
    onName: ((String) -> Unit)?,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Trust this TV?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onName != null) {
                    OutlinedTextField(name, onName, label = { Text("TV name") }, singleLine = true)
                } else {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                }
                Text("LG TV · $host")
                Text("Certificate SHA-256:")
                Text(fingerprint.chunked(FINGERPRINT_GROUP).joinToString(" "), style = MaterialTheme.typography.bodySmall)
                Text(
                    "Only trust it on your own network. Later connections must present this exact certificate. " +
                        "The TV then shows a PIN to finish pairing.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Trust & pair") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private const val FINGERPRINT_GROUP = 8
