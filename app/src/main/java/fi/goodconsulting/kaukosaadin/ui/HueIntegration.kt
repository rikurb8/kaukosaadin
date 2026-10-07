package fi.goodconsulting.kaukosaadin.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.hue.HueClient
import fi.goodconsulting.kaukosaadin.device.hue.HueDiscovery
import fi.goodconsulting.kaukosaadin.device.hue.HueFavorites
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import fi.goodconsulting.kaukosaadin.device.hue.HueProtocol
import fi.goodconsulting.kaukosaadin.device.hue.LINK_BUTTON_WAIT_SECONDS
import fi.goodconsulting.kaukosaadin.device.hue.awaitLinkButton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/** Registers the Hue Bridge kind: mDNS discovery and link-button pairing, and the bridge's lighting screen. */
internal object HueIntegration : DeviceIntegration {
    override val kind = DeviceKind.Hue

    override suspend fun scan(context: Context): List<Candidate> =
        HueDiscovery(context).scan().map { bridge ->
            HueCandidate(host = bridge.address.hostAddress.orEmpty(), name = bridge.name, detail = bridge.model)
        }

    override val addsByAddress = true

    override fun candidateAt(address: String): Candidate? =
        runCatching { HueProtocol.ipv4(address) }.getOrNull()?.let { HueCandidate(it, DeviceKind.Hue.label, null) }

    override fun controls(
        context: Context,
        device: SavedDevice,
    ): DeviceControls = HueControls(HueClient(context, device.id))
}

/** One saved bridge's stored credentials and trust, and the lighting screen that drives its lights. */
private class HueControls(
    private val client: HueClient,
) : DeviceControls {
    override val forgetDetail =
        "This forgets the bridge, its stored app key and its certificate pin. You'll need to pair it again."

    @Composable
    override fun Remote(
        padding: PaddingValues,
        remote: RemoteActions,
    ) {
        val scope = rememberCoroutineScope()
        val favorites = remember(client) { HueFavorites(client.storage) }
        val lighting = remember(client) { HueLighting.of(client, scope) }
        LightingScreen(padding, remote, lighting, favorites)
    }

    @Composable
    override fun Settings(
        device: SavedDevice,
        busy: Boolean,
        run: (suspend () -> Unit) -> Unit,
    ) {
        val status by client.status.collectAsState()
        Text(status.message)
        Text(
            "To pair again, forget this bridge and add it again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Disclosure("Details") {
            Text("Address: ${client.host ?: device.host}", style = MaterialTheme.typography.bodySmall)
            Text(
                if (client.pin != null) {
                    "Certificate: pinned to the paired bridge"
                } else {
                    "Certificate: verified by the system CA store, or not connected yet"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    override suspend fun forget(): String? = client.forget().takeUnless { it.ok }?.message
}

/** A bridge a scan returned or the operator typed the address of. */
private class HueCandidate(
    override val host: String,
    override val name: String,
    override val detail: String?,
) : Candidate {
    override val kind = DeviceKind.Hue

    @Composable
    override fun Pairing(host: PairingHost) = HuePairing(this, host)
}

/**
 * Link-button pairing: the sheet asks for the button and keeps trying for
 * [LINK_BUTTON_WAIT_SECONDS] seconds, so pressing it is all the operator does. Each attempt gets a
 * fresh client id; anything it kept is cleared unless the bridge ends up saved.
 */
@Composable
private fun HuePairing(
    candidate: HueCandidate,
    host: PairingHost,
) {
    val context = LocalContext.current.applicationContext
    var attempt by remember { mutableIntStateOf(0) }
    var timedOut by remember(attempt) { mutableStateOf(false) }
    var failure by remember(attempt) { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        val id = UUID.randomUUID().toString()
        val client = HueClient(context, id)
        var added = false
        try {
            val result = awaitLinkButton { client.pair(candidate.host) }
            added = result.ok && host.store.add(SavedDevice(id, DeviceKind.Hue, candidate.name, candidate.host))
            when {
                added -> Unit
                result.ok -> failure = SAVE_FAILED
                result.waitingForLinkButton -> timedOut = true
                else -> failure = result.message
            }
        } finally {
            if (!added) withContext(NonCancellable) { client.forget() }
        }
        if (added) host.onAdded()
    }

    val cancel = StepAction("Cancel", onClick = host.onCancel)
    val retry = StepAction("Try again") { attempt++ }
    when {
        failure != null ->
            PairingStep(DeviceKind.Hue, "Couldn't add ${candidate.name}", message = failure, primary = retry, secondary = cancel)
        timedOut ->
            PairingStep(
                DeviceKind.Hue,
                "Didn't catch that",
                message = "The bridge didn't see its button pressed. Press the round button on top of the bridge, then try again.",
                primary = retry,
                secondary = cancel,
            )
        else -> key(attempt) { PressLinkButton(candidate.name, cancel) }
    }
}

/** Asks for the link button and counts down the wait, which [awaitLinkButton] runs meanwhile. */
@Composable
private fun PressLinkButton(
    name: String,
    cancel: StepAction,
) {
    val secondsLeft by produceState(LINK_BUTTON_WAIT_SECONDS) {
        while (value > 0) {
            delay(SECOND_MS)
            value--
        }
    }
    PairingStep(
        DeviceKind.Hue,
        "Press the button on your bridge",
        message = "Press the round link button on top of $name. This finishes by itself once the bridge sees it.",
        secondary = cancel,
    ) {
        LinearProgressIndicator(
            progress = { secondsLeft / LINK_BUTTON_WAIT_SECONDS.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Waiting… ${secondsLeft}s",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val SECOND_MS = 1_000L
