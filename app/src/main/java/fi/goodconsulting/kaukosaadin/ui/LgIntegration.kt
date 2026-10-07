package fi.goodconsulting.kaukosaadin.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lgtvremote.discovery.TVDiscovery
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.LgClient
import fi.goodconsulting.kaukosaadin.device.LgProtocol
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.scanLg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Registers the LG TV kind: SSDP scan and trust/PIN pairing, the remote, and Pair again/wake extras. */
internal object LgIntegration : DeviceIntegration {
    override val kind = DeviceKind.Lg

    override suspend fun scan(
        context: Context,
        onFound: (List<Candidate>) -> Unit,
    ): List<Candidate> {
        val tvs = scanLg(context) { onFound(it.map(::candidate)) }
        return tvs.map(::candidate)
    }

    private fun candidate(tv: TVDiscovery.DiscoveredTV) =
        LgCandidate(host = tv.ip, name = if (tv.name == tv.ip) DeviceKind.Lg.label else tv.name)

    override val addsByAddress = true

    override fun candidateAt(address: String): Candidate? =
        runCatching { LgProtocol.ipv4(address) }.getOrNull()?.let { LgCandidate(it, DeviceKind.Lg.label) }

    override fun controls(
        context: Context,
        device: SavedDevice,
    ): DeviceControls = LgControls(LgClient(context, device.id))
}

/** One saved LG TV's live client and screens; the certificate pin and pairing live in [LgClient]. */
private class LgControls(
    private val client: LgClient,
) : DeviceControls {
    override val forgetDetail =
        "This forgets the TV, its pairing and wake settings. You'll need to scan and pair it again."

    @Composable
    override fun Remote(
        padding: PaddingValues,
        remote: RemoteActions,
    ) {
        LgPinDialog(client)
        LgRemote(padding, remote, client)
    }

    @Composable
    override fun Settings(
        device: SavedDevice,
        busy: Boolean,
        run: (suspend () -> Unit) -> Unit,
    ) {
        LgPinDialog(client)
        LgSettings(client, device, busy, run)
    }

    override suspend fun forget(): String? = client.forget().takeUnless { it.ok }?.message
}

/** An LG TV a scan returned or the operator typed the address of. */
private class LgCandidate(
    override val host: String,
    override val name: String,
) : Candidate {
    override val kind = DeviceKind.Lg
    override val detail = null

    @Composable
    override fun Pairing(host: PairingHost) = LgPairing(this, host)
}

/** Where one LG pairing attempt is. */
private enum class LgStep { Checking, Confirm, Connecting }

/**
 * LG pairing as one sheet: read the TV's certificate, let the operator name it and connect (the
 * certificate stays one tap away under Security details), then the PIN the TV shows. Connect is
 * the trust decision: the certificate read here is the one later connections are pinned to. Each
 * attempt gets a fresh client id; anything it kept is cleared unless the TV ends up saved.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // One attempt's steps share one state.
@Composable
private fun LgPairing(
    candidate: LgCandidate,
    host: PairingHost,
) {
    val context = LocalContext.current.applicationContext
    var attempt by remember { mutableIntStateOf(0) }
    val id = remember(attempt) { UUID.randomUUID().toString() }
    val client = remember(id) { LgClient(context, id) }
    val awaitingPin by client.awaitingPin.collectAsState()
    var step by remember(attempt) { mutableStateOf(LgStep.Checking) }
    var fingerprint by remember(attempt) { mutableStateOf("") }
    var failure by remember(attempt) { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf(candidate.name) }
    var pin by remember(attempt) { mutableStateOf("") }
    var pinError by remember(attempt) { mutableStateOf<String?>(null) }
    var submitted by remember(attempt) { mutableStateOf(false) }
    val confirmed = remember(attempt) { CompletableDeferred<Unit>() }

    DisposableEffect(client) { onDispose { client.cancelPairing() } }
    LaunchedEffect(client) {
        var added = false
        try {
            val inspected = client.inspect(candidate.host)
            if (inspected == null) {
                failure = "Couldn't reach the TV. Make sure it's switched on and on the same Wi-Fi."
                return@LaunchedEffect
            }
            fingerprint = inspected
            step = LgStep.Confirm
            confirmed.await()
            step = LgStep.Connecting
            val result = client.pair(candidate.host, inspected)
            added = result.ok && host.store.add(SavedDevice(id, DeviceKind.Lg, name, candidate.host))
            if (!added) failure = if (result.ok) SAVE_FAILED else result.message
        } finally {
            if (!added) withContext(NonCancellable) { client.forget() }
        }
        if (added) host.onAdded()
    }

    fun submit() {
        val result = client.submitPin(pin)
        submitted = result.ok
        pinError = result.message.takeUnless { result.ok }
    }

    val cancel = StepAction("Cancel", onClick = host.onCancel)
    when {
        failure != null ->
            PairingStep(
                DeviceKind.Lg,
                "Couldn't add ${candidate.name}",
                message = failure,
                primary = StepAction("Try again") { attempt++ },
                secondary = cancel,
            )
        step == LgStep.Checking ->
            PairingStep(DeviceKind.Lg, "Checking ${candidate.name}…", busy = true, secondary = cancel)
        step == LgStep.Confirm ->
            LgConfirmStep(candidate.name, name, fingerprint, cancel, onName = { name = it }, onConnect = { confirmed.complete(Unit) })
        awaitingPin ->
            PairingStep(
                DeviceKind.Lg,
                "Enter the PIN",
                message = "Type the PIN shown on the TV.",
                error = pinError,
                primary = StepAction("Continue", enabled = pin.length >= LG_PIN_MIN_LENGTH, onClick = ::submit),
                secondary = cancel,
            ) {
                PinField(pin, { pin = it }, maxLength = LG_PIN_MAX_LENGTH, onDone = ::submit)
            }
        else ->
            PairingStep(
                DeviceKind.Lg,
                if (submitted) "Checking the PIN…" else "Connecting to $name…",
                message = if (submitted) null else "A PIN will appear on your TV in a moment.",
                busy = true,
                secondary = cancel,
            )
    }
}

/** The trust confirmation, without inspecting or pairing with a TV. */
@Composable
internal fun LgConfirmStep(
    discoveredName: String,
    name: String,
    fingerprint: String,
    cancel: StepAction,
    onName: (String) -> Unit,
    onConnect: () -> Unit,
) {
    PairingStep(
        DeviceKind.Lg,
        "Add $discoveredName",
        message = "The TV will show a PIN to finish.",
        primary = StepAction("Connect", onClick = onConnect),
        secondary = cancel,
    ) {
        OutlinedTextField(name, onName, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LgSecurityDetails(fingerprint)
    }
}

private const val LG_PIN_MIN_LENGTH = 4

/** LG verifies registration with Connect and opens a fresh, pinned TLS session per press. */
@Composable
private fun LgRemote(
    padding: PaddingValues,
    remote: RemoteActions,
    client: LgClient,
) {
    val scope = rememberCoroutineScope()
    val status by client.status.collectAsState()
    val ready by client.ready.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LgClient.Result?>(null) }

    fun run(block: suspend () -> LgClient.Result) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                result = block()
            } finally {
                busy = false
            }
        }
    }
    RemoteScreen(
        contentPadding = padding,
        devices = remote.devices,
        current = remote.current,
        layout = remote.layout,
        ready = ready,
        busy = busy,
        powerEnabled = client.mac.isNotEmpty() && client.broadcast.isNotEmpty() && client.fingerprint.isNotEmpty(),
        status = (if (busy) status else result ?: status).message,
        onSelect = remote.onSelect,
        onAddDevice = remote.onAddDevice,
        onDevices = remote.onDevices,
        onConnect = { if (client.fingerprint.isEmpty()) remote.onSettings() else run { client.connect() } },
        onApps = null,
        onSettings = remote.onSettings,
        onGeneralSettings = remote.onGeneralSettings,
        onPower = { run { client.send(LgProtocol.Action.Wake) } },
        onKey = { key, _ -> key.lg?.let { action -> run { client.send(action) } } },
    )
}

/** Pair again (read the certificate again and enter a new PIN) and the optional Wake-on-LAN settings. */
@Composable
private fun LgSettings(
    client: LgClient,
    device: SavedDevice,
    busy: Boolean,
    run: (suspend () -> Unit) -> Unit,
) {
    val status by client.status.collectAsState()
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var mac by remember { mutableStateOf(client.mac) }
    var broadcast by remember { mutableStateOf(client.broadcast) }
    val wakeSaved = mac.isNotEmpty() && broadcast.isNotEmpty() && mac == client.mac && broadcast == client.broadcast

    fingerprint?.let { inspected ->
        LgTrustDialog(
            name = device.name,
            fingerprint = inspected,
            onTrust = {
                fingerprint = null
                run { client.pair(device.host, inspected) }
            },
            onCancel = { fingerprint = null },
        )
    }
    Text(status.message)
    OutlinedButton(enabled = !busy, onClick = { run { fingerprint = client.inspect(device.host) } }) { Text("Pair again") }
    Disclosure("Wake from sleep (advanced)") {
        Text(
            "To turn the TV on from the remote, enter the TV's network MAC address and your network's broadcast address.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(mac, { mac = it }, label = { Text("TV network MAC") }, enabled = !busy, singleLine = true)
        OutlinedTextField(broadcast, { broadcast = it }, label = { Text("Broadcast address") }, enabled = !busy, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { run { client.saveWake(device.host, mac, broadcast) } }) { Text("Save") }
            OutlinedButton(enabled = !busy && wakeSaved, onClick = { run { client.send(LgProtocol.Action.Wake) } }) { Text("Wake TV") }
        }
    }
}
