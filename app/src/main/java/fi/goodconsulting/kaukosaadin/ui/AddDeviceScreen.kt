package fi.goodconsulting.kaukosaadin.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.DeviceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * One scan for every supported device, listed together: LG TVs, Apple TVs and Hue Bridges as they
 * answer. Picking one opens its kind's pairing in a sheet; nothing pairs automatically. A device the
 * scan missed can be added by its address from "Can't find your device?".
 */
@Composable
fun AddDeviceScreen(
    padding: PaddingValues,
    store: DeviceStore,
    onBack: () -> Unit,
    onAdded: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val saved by store.devices.collectAsState()
    var scanToken by remember { mutableIntStateOf(0) }
    var pairing by remember { mutableStateOf<Candidate?>(null) }
    var manual by remember { mutableStateOf(false) }
    val view = mergeScan(rememberScan(scanToken), saved)

    pairing?.let { candidate ->
        PairingSheet(
            candidate,
            PairingHost(store, onAdded = {
                pairing = null
                onAdded()
            }, onCancel = { pairing = null }),
        )
    }
    if (manual) {
        ManualAddressSheet(onDismiss = { manual = false }, onContinue = {
            manual = false
            pairing = it
        })
    }

    AddDeviceContent(
        padding,
        view,
        onBack = onBack,
        onScanAgain = { scanToken++ },
        onPair = { pairing = it },
        onManual = { manual = true },
    )
}

/** The scan's visible state, independent of discovery and pairing lifetimes. */
@Composable
internal fun AddDeviceContent(
    padding: PaddingValues,
    view: ScanView,
    onBack: () -> Unit,
    onScanAgain: () -> Unit,
    onPair: (Candidate) -> Unit,
    onManual: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("Close") }
        DeviceIllustration(Modifier.height(112.dp))
        Text("Let's find your devices", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Keep your device switched on and this phone on the same Wi-Fi. Tap a device to connect.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ScanStatus(view, onScanAgain = onScanAgain)
        view.rows.forEach { row ->
            DeviceCard(
                kind = row.candidate.kind,
                title = row.candidate.name,
                subtitle = row.subtitle,
                onClick = if (row.saved) null else ({ onPair(row.candidate) }),
                dimmed = row.saved,
                tag = if (row.saved) "Added" else null,
                trailing =
                    if (row.saved) {
                        null
                    } else {
                        { Text("→", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clearAndSetSemantics {}) }
                    },
            )
        }
        view.failureNote?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!view.scanning && view.newCount == 0) NotFoundTips()
        TextButton(onClick = onManual) { Text("Can't find your device?") }
    }
}

/**
 * Every registered kind's scan, side by side, restarted whenever [token] changes. Each kind's
 * outcome lands as soon as that kind finishes, and each device of that kind is listed as soon as the
 * kind's scan reports it, so the list fills in while the scans are still running.
 */
@Suppress("TooGenericExceptionCaught") // Any failure of one kind's scan is that kind's failure; the cause is logged.
@Composable
private fun rememberScan(token: Int): Map<DeviceKind, ScanOutcome> {
    val context = LocalContext.current.applicationContext
    val outcomes = remember { mutableStateMapOf<DeviceKind, ScanOutcome>() }
    LaunchedEffect(token) {
        DeviceIntegrations.all.forEach { outcomes[it.kind] = ScanOutcome.Scanning(emptyList()) }
        coroutineScope {
            DeviceIntegrations.all.forEach { integration ->
                launch {
                    val onFound = { found: List<Candidate> -> outcomes[integration.kind] = ScanOutcome.Scanning(found) }
                    outcomes[integration.kind] =
                        try {
                            ScanOutcome.Found(integration.scan(context, onFound))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The cause is for logcat; the screen only says what the operator can try.
                            Log.w(SCAN_TAG, "${integration.kind} scan failed: ${e.javaClass.simpleName}: ${e.message}")
                            ScanOutcome.Failed
                        }
                }
            }
        }
    }
    return outcomes
}

/** A progress bar while any kind is still scanning; afterwards the outcome and Scan again. */
@Composable
private fun ScanStatus(
    view: ScanView,
    onScanAgain: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (view.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(view.headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!view.scanning) TextButton(onClick = onScanAgain) { Text("Scan again") }
        }
    }
}

@Composable
private fun NotFoundTips() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "Make sure the TV or bridge is switched on.",
            "Check that this phone is on the same Wi-Fi.",
            "Wake an Apple TV with its own remote first.",
        ).forEach {
            Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Add by address: the kinds that can be added that way, and the address field. Continue hands the
 * kind's candidate to pairing, exactly as if the scan had found it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualAddressSheet(
    onDismiss: () -> Unit,
    onContinue: (Candidate) -> Unit,
) {
    val kinds = DeviceIntegrations.all.filter { it.addsByAddress }
    var selected by remember { mutableStateOf(kinds.first()) }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val candidate = selected.candidateAt(address.trim())
        if (candidate == null) {
            error = "Enter a local address made of numbers, like 192.168.1.20."
        } else {
            onContinue(candidate)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add by address", style = MaterialTheme.typography.headlineSmall)
            Text(
                "If your device didn't show up, enter its IP address. You'll find it in the device's network settings " +
                    "or your router's list of devices.",
                style = MaterialTheme.typography.bodyLarge,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { index, integration ->
                    SegmentedButton(
                        selected = integration == selected,
                        onClick = { selected = integration },
                        shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                    ) { Text(integration.kind.label) }
                }
            }
            OutlinedTextField(
                address,
                {
                    address = it
                    error = null
                },
                label = { Text("IP address") },
                placeholder = { Text("192.168.1.20") },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = address.isNotBlank(),
                onClick = ::submit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue") }
            Text(
                "Apple TVs can only be added when the scan finds them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal const val SAVE_FAILED = "Paired, but the device could not be saved. Try again."

private const val SCAN_TAG = "KaukosaadinScan"
