package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import kotlinx.coroutines.delay

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
