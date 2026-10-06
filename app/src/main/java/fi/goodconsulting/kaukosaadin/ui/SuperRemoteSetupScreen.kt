package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.AppleTvApp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.hue.HueClient
import fi.goodconsulting.kaukosaadin.device.hue.HueGroup
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import fi.goodconsulting.kaukosaadin.device.hue.HueResult
import kotlinx.coroutines.launch

/** Shown when a saved bridge has no stored app key, so its rooms and zones cannot be read. */
private const val UNPAIRED_SETUP_BRIDGE =
    "This bridge is not paired on this phone. Forget it and add it again to store an app key."

/** The app names setup offers by itself; the bundle ids always come from the TV's report, never here. */
private val PREFERRED_APP_NAMES = listOf("YouTube", "Yle Areena")

/**
 * The app shortcuts setup chooses when the operator has not chosen any: the preferred apps [reported]
 * actually carries, in that order, and only those. [reported] is the TV's own app list, so nothing
 * here names an app the TV did not list.
 */
internal fun preferredShortcuts(reported: List<AppleTvApp>): List<AppleTvApp> =
    PREFERRED_APP_NAMES.mapNotNull { name -> reported.firstOrNull { it.name.equals(name, ignoreCase = true) } }

/**
 * The shortcuts to keep given the TV's [reported] apps and the operator's [stored] choice: the stored
 * choice unchanged, and the preferred apps only while nothing is stored. An empty or unhelpful report
 * leaves [stored] alone rather than clearing it.
 */
internal fun shortcutsFor(
    reported: List<AppleTvApp>,
    stored: List<AppleTvApp>,
): List<AppleTvApp> = stored.ifEmpty { preferredShortcuts(reported) }

/**
 * The setup screen's choices, read from and written through the Super remote's own store, so a choice
 * is visible on the Super remote without a restart. Every write replaces [SuperRemoteBindings]; the
 * device picker's selection is not reachable from here, so choosing cannot move it.
 */
internal class SuperRemoteSetup(
    private val read: () -> SuperRemoteBindings,
    private val write: (SuperRemoteBindings) -> Boolean,
) {
    /** The bindings as stored now; the screen reads them through the store for the same reason. */
    val bindings: SuperRemoteBindings get() = read()

    fun chooseAppleTv(deviceId: String): Boolean = write(read().copy(appleTvDeviceId = deviceId))

    /** Chooses [deviceId] as the bridge; another bridge cannot keep the old bridge's room or zone. */
    fun chooseBridge(deviceId: String): Boolean {
        val current = read()
        if (current.hueDeviceId == deviceId) return true
        return write(current.copy(hueDeviceId = deviceId, hueTargetId = null, hueTargetName = null))
    }

    fun chooseLightTarget(
        deviceId: String,
        groupedLightId: String,
        name: String,
    ): Boolean = write(read().copy(hueDeviceId = deviceId, hueTargetId = groupedLightId, hueTargetName = name))

    fun chooseShortcuts(shortcuts: List<AppleTvApp>): Boolean = write(read().copy(shortcuts = shortcuts))
}

/**
 * Setup and reselection for the Super remote's bindings: the saved Apple TV, the Hue Bridge's room or
 * zone, and the two app shortcuts, each in its own section so an unfinished one does not stop the
 * others. Every choice is written through [setup] — the same store the Super remote reads — and the
 * only way back is [onBack], which changes nothing. Pairing new hardware stays in the Add device flow,
 * reached through [onAddDevice].
 */
@Composable
internal fun SuperRemoteSetupScreen(
    padding: PaddingValues,
    devices: List<SavedDevice>,
    bindings: SuperRemoteBindings,
    setup: SuperRemoteSetup,
    onAddDevice: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val appleTv = devices.firstOrNull { it.kind == DeviceKind.AppleTv && it.id == bindings.appleTvDeviceId }
    val bridge = devices.firstOrNull { it.kind == DeviceKind.Hue && it.id == bindings.hueDeviceId }
    val companion = remember(appleTv?.id) { appleTv?.let { CompanionClient(context, it.id) } }
    val lighting = remember(bridge?.id) { bridge?.let { HueLighting.of(HueClient(context, it.id), scope) } }
    var saveFailed by remember { mutableStateOf(false) }
    val save: (Boolean) -> Unit = { stored -> if (!stored) saveFailed = true }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set up Super remote", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("Back to Super remote") }
        Text("Each section is independent: an unfinished one does not stop the others.", style = MaterialTheme.typography.bodySmall)
        if (saveFailed) Text("Could not save the choice on this phone. Try again.", style = MaterialTheme.typography.bodyMedium)
        SetupAppleTvChoices(
            appleTvs = devices.filter { it.kind == DeviceKind.AppleTv },
            chosen = appleTv,
            setup = setup,
            save = save,
            onAddDevice = onAddDevice,
        )
        SetupShortcutChoices(appleTv = appleTv, client = companion, chosen = bindings.shortcuts, setup = setup, save = save)
        SetupBridgeChoices(
            bridges = devices.filter { it.kind == DeviceKind.Hue },
            chosen = bridge,
            setup = setup,
            save = save,
            onAddDevice = onAddDevice,
        )
        SetupTargetChoices(bridge = bridge, lighting = lighting, chosen = bindings, setup = setup, save = save)
        TextButton(onClick = onAddDevice) { Text("Pair a new device") }
    }
}

/** The saved Apple TVs the Super remote can drive; tapping one binds it. */
@Composable
private fun SetupAppleTvChoices(
    appleTvs: List<SavedDevice>,
    chosen: SavedDevice?,
    setup: SuperRemoteSetup,
    save: (Boolean) -> Unit,
    onAddDevice: () -> Unit,
) {
    Text("Apple TV", style = MaterialTheme.typography.titleMedium)
    if (appleTvs.isEmpty()) {
        Text("No saved Apple TV yet. Pair one to launch its apps.", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onAddDevice) { Text("Add Apple TV") }
        return
    }
    appleTvs.forEach { device ->
        val label = if (device.id == chosen?.id) "✓ ${device.name} · ${device.host}" else "${device.name} · ${device.host}"
        OutlinedButton(onClick = { save(setup.chooseAppleTv(device.id)) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}

/** The chosen Apple TV's reported apps, up to two of which become the shortcuts. */
@Composable
private fun SetupShortcutChoices(
    appleTv: SavedDevice?,
    client: CompanionClient?,
    chosen: List<AppleTvApp>,
    setup: SuperRemoteSetup,
    save: (Boolean) -> Unit,
) {
    Text("App shortcuts", style = MaterialTheme.typography.titleMedium)
    if (appleTv == null || client == null) {
        Text("Choose an Apple TV above first; its report supplies the shortcuts.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val apps by client.apps.collectAsState()
    val status by client.status.collectAsState()
    val scope = rememberCoroutineScope()
    // The TV reports its apps only while awake; refresh on open and whenever the chosen TV changes.
    LaunchedEffect(client) { refreshShortcuts(client, setup, save) }
    Text(status.message, style = MaterialTheme.typography.bodySmall)
    if (apps.isEmpty()) {
        Text("No apps reported yet. The Apple TV may be asleep or off the network.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { scope.launch { refreshShortcuts(client, setup, save) } }) { Text("Refresh apps") }
        return
    }
    apps.forEach { app ->
        val label = if (chosen.any { it.bundleId == app.bundleId }) "✓ ${app.name}" else app.name
        OutlinedButton(onClick = { save(toggleShortcut(setup, app)) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}

/** The saved Hue Bridges; tapping one binds it, and its rooms and zones then list below. */
@Composable
private fun SetupBridgeChoices(
    bridges: List<SavedDevice>,
    chosen: SavedDevice?,
    setup: SuperRemoteSetup,
    save: (Boolean) -> Unit,
    onAddDevice: () -> Unit,
) {
    Text("Hue Bridge", style = MaterialTheme.typography.titleMedium)
    if (bridges.isEmpty()) {
        Text("No saved Hue Bridge yet. Pair one to choose its room or zone.", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onAddDevice) { Text("Add Hue Bridge") }
        return
    }
    bridges.forEach { bridge ->
        val label = if (bridge.id == chosen?.id) "✓ ${bridge.name} · ${bridge.host}" else "${bridge.name} · ${bridge.host}"
        OutlinedButton(onClick = { save(setup.chooseBridge(bridge.id)) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}

/** The chosen bridge's own rooms and zones, limited to the ones that carry a commandable grouped light. */
@Composable
private fun SetupTargetChoices(
    bridge: SavedDevice?,
    lighting: HueLighting?,
    chosen: SuperRemoteBindings,
    setup: SuperRemoteSetup,
    save: (Boolean) -> Unit,
) {
    Text("Room or zone", style = MaterialTheme.typography.titleMedium)
    if (bridge == null) {
        Text("Choose a Hue Bridge above to list the rooms and zones it reports.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (lighting == null) {
        Text(UNPAIRED_SETUP_BRIDGE, style = MaterialTheme.typography.bodyMedium)
        return
    }
    var targets by remember(bridge.id) { mutableStateOf<List<HueGroup>?>(null) }
    var failure by remember(bridge.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(lighting) {
        val (groups, message) = loadTargets(lighting)
        targets = groups
        failure = message
    }
    val groups = targets
    when {
        groups == null -> Text("Reading rooms and zones from the bridge…", style = MaterialTheme.typography.bodySmall)
        groups.isEmpty() -> Text("The bridge reports no room or zone this phone can command.", style = MaterialTheme.typography.bodyMedium)
        else ->
            groups.forEach { group ->
                val id = group.groupedLightId ?: return@forEach
                val label = if (id == chosen.hueTargetId) "✓ ${group.name}" else group.name
                OutlinedButton(
                    onClick = { save(setup.chooseLightTarget(bridge.id, id, group.name)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(label) }
            }
    }
    failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

/** Adds or removes [app] among the shortcuts, read live from the store so a tap cannot drop a parallel choice. */
private fun toggleShortcut(
    setup: SuperRemoteSetup,
    app: AppleTvApp,
): Boolean {
    val current = setup.bindings.shortcuts
    val next =
        if (current.any { it.bundleId == app.bundleId }) {
            current.filterNot { it.bundleId == app.bundleId }
        } else {
            (current + app).take(TWO_SHORTCUTS)
        }
    return setup.chooseShortcuts(next)
}

/** Refreshes the TV's apps, then takes the preferred shortcuts only while none is stored; never clears a choice. */
private suspend fun refreshShortcuts(
    client: CompanionClient,
    setup: SuperRemoteSetup,
    save: (Boolean) -> Unit,
) {
    client.appList()
    if (setup.bindings.shortcuts.isEmpty()) {
        save(setup.chooseShortcuts(shortcutsFor(client.apps.value, setup.bindings.shortcuts)))
    }
}

/** The bridge's rooms and zones that carry a grouped light, and the first failure to show, if any. */
private suspend fun loadTargets(lighting: HueLighting): Pair<List<HueGroup>, String?> {
    val rooms = lighting.rooms()
    val zones = lighting.zones()
    val groups =
        ((rooms as? HueResult.Ok)?.value.orEmpty() + (zones as? HueResult.Ok)?.value.orEmpty())
            .filter { it.groupedLightId != null }
    return groups to ((rooms as? HueResult.Failure)?.message ?: (zones as? HueResult.Failure)?.message)
}

private const val TWO_SHORTCUTS = 2
