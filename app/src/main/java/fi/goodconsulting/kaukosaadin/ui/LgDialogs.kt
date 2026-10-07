package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.LgClient

/** Shared by Pair again and the remote's Connect button, which re-pairs if the TV dropped its key. */
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

    fun submit() {
        val submitted = client.submitPin(pin)
        pinError = if (submitted.ok) "" else submitted.message
        if (submitted.ok) pin = ""
    }
    if (awaitingPin) {
        AlertDialog(
            onDismissRequest = { client.cancelPairing() },
            title = { Text("Enter the TV PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Type the PIN shown on the TV.")
                    PinField(pin, { pin = it }, maxLength = LG_PIN_MAX_LENGTH, onDone = ::submit)
                    if (pinError.isNotEmpty()) Text(pinError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = ::submit) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { client.cancelPairing() }) { Text("Cancel") } },
        )
    }
}

/**
 * Pair again's trust decision: the TV presented a certificate, which later connections are pinned to.
 * First trust cannot prove identity, so the fingerprint and what to check stay one tap away.
 */
@Composable
fun LgTrustDialog(
    name: String,
    fingerprint: String,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pair $name again?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The TV will show a PIN to finish pairing.")
                LgSecurityDetails(fingerprint)
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** The certificate the TV presented, collapsed: what gets pinned and why it matters. */
@Composable
internal fun LgSecurityDetails(fingerprint: String) {
    Disclosure("Security details") {
        Text(
            "Only add a TV on your own network. From now on the app only talks to a TV presenting this certificate:",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            fingerprint.chunked(FINGERPRINT_GROUP).joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val FINGERPRINT_GROUP = 8

/** LG TVs show a 4–8 digit PIN; the client checks the length on submit. */
internal const val LG_PIN_MAX_LENGTH = 8
