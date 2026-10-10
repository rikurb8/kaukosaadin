package fi.goodconsulting.kaukosaadin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.goodconsulting.kaukosaadin.device.DeviceKind

/** Hosts [candidate]'s pairing steps in a bottom sheet; dismissing it cancels pairing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairingSheet(
    candidate: Candidate,
    host: PairingHost,
) {
    ModalBottomSheet(
        onDismissRequest = host.onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        key(candidate) { candidate.Pairing(host) }
    }
}

/** A button on a pairing step. */
internal class StepAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * One pairing step, laid out the same for every kind: a hardware sketch and a short [title], one
 * plain [message], the step's own [content], an indeterminate bar while [busy], an inline [error]
 * and up to two buttons.
 */
@Composable
internal fun PairingStep(
    kind: DeviceKind,
    title: String,
    message: String? = null,
    error: String? = null,
    busy: Boolean = false,
    primary: StepAction? = null,
    secondary: StepAction? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeviceIllustration(Modifier.height(112.dp), kind = kind)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        primary?.let {
            Button(onClick = it.onClick, enabled = it.enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(it.label) }
        }
        secondary?.let {
            TextButton(onClick = it.onClick, enabled = it.enabled, modifier = Modifier.fillMaxWidth()) { Text(it.label) }
        }
    }
}

/**
 * A large, centred field for the PIN a device shows on its screen. Takes digits only, up to
 * [maxLength], and asks for focus so the number pad opens straight away.
 */
@Composable
internal fun PinField(
    value: String,
    onValue: (String) -> Unit,
    maxLength: Int,
    enabled: Boolean = true,
    onDone: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.filter(Char::isDigit).take(maxLength)) },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 8.sp, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

/** A collapsed "more" section: technical details people rarely need, one tap away. */
@Composable
internal fun Disclosure(
    label: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = !open }) { Text(if (open) "$label ▴" else "$label ▾") }
        AnimatedVisibility(open) {
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}
