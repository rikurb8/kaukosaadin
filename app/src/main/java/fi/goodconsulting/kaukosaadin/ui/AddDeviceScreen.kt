package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lgtvremote.discovery.TVDiscovery
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.DeviceStore
import fi.goodconsulting.kaukosaadin.device.LgClient
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.companion.CompanionDiscovery
import fi.goodconsulting.kaukosaadin.device.scanLg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** An LG TV whose certificate was inspected and now awaits the user's trust decision. */
private class LgCandidate(
    val client: LgClient,
    val id: String,
    val host: String,
    val fingerprint: String,
)

/**
 * One scan for every supported device: LG TVs over SSDP and Apple TVs over Companion mDNS, side by
 * side. Picking one pairs it with the PIN it shows and saves it; nothing pairs automatically.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // Scan, both pairing flows and their cleanup share one state.
@Composable
fun AddDeviceScreen(
    padding: PaddingValues,
    store: DeviceStore,
    discovery: CompanionDiscovery,
    onBack: () -> Unit,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val saved by store.devices.collectAsState()
    BackHandler(onBack = onBack)
    var scanning by remember { mutableStateOf(false) }
    var lgFound by remember { mutableStateOf(emptyList<TVDiscovery.DiscoveredTV>()) }
    var appleFound by remember { mutableStateOf(emptyList<CompanionDiscovery.Device>()) }
    var lgScan by remember { mutableStateOf("") }
    var appleScan by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var applePairing by remember { mutableStateOf<CompanionClient?>(null) }
    var lgCandidate by remember { mutableStateOf<LgCandidate?>(null) }
    var lgName by remember { mutableStateOf("") }
    var manualAddress by remember { mutableStateOf("") }

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

    // Each scan reports on its own, so one failing or finding nothing never hides the other's devices.
    @Suppress("TooGenericExceptionCaught") // Any scan failure becomes a retry hint, never a crash.
    fun scan() {
        if (scanning) return
        scanning = true
        lgScan = "Searching for LG TVs…"
        appleScan = "Searching for Apple TVs…"
        scope.launch {
            try {
                coroutineScope {
                    launch {
                        lgScan =
                            try {
                                lgFound = scanLg(context)
                                if (lgFound.isEmpty()) "No LG TVs replied. Turn the TV on, or enter its address below." else ""
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                "LG scan failed. Check Wi-Fi/LAN access, then scan again."
                            }
                    }
                    launch {
                        appleScan =
                            try {
                                appleFound = discovery.scan()
                                if (appleFound.isEmpty()) "No Apple TVs found. Wake it with its own remote, then scan again." else ""
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                "Apple TV scan failed. Check Wi-Fi/LAN access, then scan again."
                            }
                    }
                }
            } finally {
                scanning = false
            }
        }
    }
    LaunchedEffect(Unit) { scan() }

    fun isSaved(
        kind: DeviceKind,
        host: String,
    ) = saved.any { it.kind == kind && it.host == host }

    fun pairApple(device: CompanionDiscovery.Device) =
        run {
            val id = UUID.randomUUID().toString()
            val client = CompanionClient(context, id)
            val host = device.address.hostAddress.orEmpty()
            var added = false
            applePairing = client
            message = "Pairing with ${device.name}…"
            try {
                val result = client.pair(device)
                message = result.message
                added = result.ok && store.add(SavedDevice(id, DeviceKind.AppleTv, device.name, host))
                if (result.ok && !added) message = SAVE_FAILED
            } finally {
                applePairing = null
                if (!added) withContext(NonCancellable) { client.forget() }
            }
            if (added) onAdded()
        }

    fun inspectLg(
        host: String,
        name: String,
    ) = run {
        val id = UUID.randomUUID().toString()
        val client = LgClient(context, id)
        message = "Reading the certificate of $host…"
        val fingerprint = client.inspect(host)
        if (fingerprint == null) {
            message = client.status.value.message
            client.forget()
        } else {
            message = ""
            lgName = name
            lgCandidate = LgCandidate(client, id, host, fingerprint)
        }
    }

    fun cancelLg() {
        val candidate = lgCandidate ?: return
        lgCandidate = null
        run { candidate.client.forget() }
    }

    fun pairLg(candidate: LgCandidate) =
        run {
            var added = false
            try {
                val result = candidate.client.pair(candidate.host, candidate.fingerprint)
                message = result.message
                added = result.ok && store.add(SavedDevice(candidate.id, DeviceKind.Lg, lgName, candidate.host))
                if (result.ok && !added) message = SAVE_FAILED
            } finally {
                lgCandidate = null
                if (!added) withContext(NonCancellable) { candidate.client.forget() }
            }
            if (added) onAdded()
        }

    applePairing?.let { AppleTvPinDialog(it) }
    lgCandidate?.let { candidate ->
        LgPinDialog(candidate.client)
        // Hidden while pairing runs, so the TV's PIN dialog is the only one on screen.
        if (!busy) {
            LgTrustDialog(
                name = lgName,
                host = candidate.host,
                fingerprint = candidate.fingerprint,
                onName = { lgName = it },
                onTrust = { pairLg(candidate) },
                onCancel = ::cancelLg,
            )
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Add device", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("Done") }
        Text("Finds LG webOS TVs and Apple TVs on your Wi-Fi. Pick one to pair it with the PIN it shows.")
        Button(enabled = !scanning, onClick = ::scan) { Text(if (scanning) "Scanning…" else "Scan again") }
        if (message.isNotEmpty()) Text(message)
        Text("Apple TV", style = MaterialTheme.typography.titleMedium)
        appleFound.forEach { device ->
            val host = device.address.hostAddress.orEmpty()
            FoundDevice(device.name, host, isSaved(DeviceKind.AppleTv, host), enabled = !busy) { pairApple(device) }
        }
        if (appleScan.isNotEmpty()) Text(appleScan, style = MaterialTheme.typography.bodySmall)
        Text("LG TV", style = MaterialTheme.typography.titleMedium)
        lgFound.forEach { tv ->
            val name = if (tv.name == tv.ip) DeviceKind.Lg.label else tv.name
            FoundDevice(name, tv.ip, isSaved(DeviceKind.Lg, tv.ip), enabled = !busy) { inspectLg(tv.ip, name) }
        }
        if (lgScan.isNotEmpty()) Text(lgScan, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                manualAddress,
                { manualAddress = it.trim() },
                label = { Text("Enter LG TV address") },
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = !busy && manualAddress.isNotEmpty(), onClick = {
                inspectLg(manualAddress, DeviceKind.Lg.label)
            }) { Text("Add") }
        }
        Text(
            "Names are what devices advertise, not proof of identity; the PIN on the screen is what proves it.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onBack) { Text("Done") }
    }
}

@Composable
private fun FoundDevice(
    name: String,
    host: String,
    saved: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    OutlinedButton(enabled = enabled && !saved, onClick = onPick, modifier = Modifier.fillMaxWidth()) {
        Text(if (saved) "$name · $host · Saved" else "$name · $host")
    }
}

private const val SAVE_FAILED = "Paired, but the device could not be saved. Try again."
