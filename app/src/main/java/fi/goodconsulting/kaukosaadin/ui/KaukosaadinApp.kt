package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.DeviceStore
import fi.goodconsulting.kaukosaadin.device.LgClient
import fi.goodconsulting.kaukosaadin.device.LgProtocol
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.companion.CompanionDiscovery
import fi.goodconsulting.kaukosaadin.device.companion.HidCommand
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the app is; every screen but the remote returns to it. */
private enum class Screen { Remote, AddDevice, DeviceSettings, Apps, GeneralSettings }

/** Remote keys and the command each device kind sends; LG has no Home, Play/Pause or volume key. */
internal enum class RemoteKey(
    val lg: LgProtocol.Action?,
    val hid: HidCommand,
) {
    Up(LgProtocol.Action.Up, HidCommand.Up),
    Down(LgProtocol.Action.Down, HidCommand.Down),
    Left(LgProtocol.Action.Left, HidCommand.Left),
    Right(LgProtocol.Action.Right, HidCommand.Right),
    Select(LgProtocol.Action.Select, HidCommand.Select),
    Back(LgProtocol.Action.Back, HidCommand.Menu),
    Home(null, HidCommand.Home),
    PlayPause(null, HidCommand.PlayPause),
    VolumeDown(null, HidCommand.VolumeDown),
    VolumeUp(null, HidCommand.VolumeUp),
}

/** Arrows on the dial: glyph rotation, placement, and the quarter that tilts when held. Angles
 *  are a 90°-per-quarter geometry table, not tunables. */
@Suppress("MagicNumber")
internal enum class Direction(
    val key: RemoteKey,
    val rotation: Float,
    val alignment: Alignment,
    val wedgeStart: Float,
) {
    Up(RemoteKey.Up, 0f, Alignment.TopCenter, -135f),
    Right(RemoteKey.Right, 90f, Alignment.CenterEnd, -45f),
    Down(RemoteKey.Down, 180f, Alignment.BottomCenter, 45f),
    Left(RemoteKey.Left, 270f, Alignment.CenterStart, 135f),
    ;

    val label get() = key.name
}

/**
 * The saved devices and the one the remote drives. Only that device's client exists; switching
 * devices builds a fresh one, which drops the previous device's session and status.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // Screen routing and the Apple session lifecycle share one state.
@Composable
fun KaukosaadinApp() {
    val context = LocalContext.current.applicationContext
    val store = remember { DeviceStore(context) }
    val discovery = remember { CompanionDiscovery(context) }
    val devices by store.devices.collectAsState()
    val selectedId by store.selectedId.collectAsState()
    val current = DeviceStore.current(devices, selectedId)
    val lg = remember(current?.id) { current?.takeIf { it.kind == DeviceKind.Lg }?.let { LgClient(context, it.id) } }
    val apple = remember(current?.id) { current?.takeIf { it.kind == DeviceKind.AppleTv }?.let { CompanionClient(context, it.id) } }
    val preferences = remember { context.getSharedPreferences("general_settings", android.content.Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(AppTheme.fromId(preferences.getString("theme", null))) }
    var layout by remember { mutableStateOf(AppLayout.fromId(preferences.getString("layout", null))) }
    var screen by rememberSaveable { mutableStateOf(Screen.Remote) }
    var appleConnecting by remember { mutableStateOf(false) }

    val activity = LocalActivity.current
    // The Apple session stays open while the apps screen is shown: it needs the TV to list and launch.
    val appleRemoteVisible = apple != null && (screen == Screen.Remote || screen == Screen.Apps)
    val sessionClient by rememberUpdatedState(apple.takeIf { appleRemoteVisible })
    LaunchedEffect(activity) {
        snapshotFlow { sessionClient }.collectLatest { client ->
            if (client != null && activity is LifecycleOwner) {
                activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    try {
                        appleConnecting = true
                        try {
                            client.connect()
                        } finally {
                            appleConnecting = false
                        }
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { client.disconnect() }
                    }
                }
            }
        }
    }

    val colors =
        when (theme) {
            AppTheme.Classic -> if (isSystemInDarkTheme()) DarkColors else LightColors
            AppTheme.HackerMan -> HackerManColors
        }
    val window = LocalActivity.current?.window
    val view = LocalView.current
    SideEffect {
        window?.let {
            val bars = WindowCompat.getInsetsController(it, view)
            val light = colors.background.luminance() > 0.5f
            bars.isAppearanceLightStatusBars = light
            bars.isAppearanceLightNavigationBars = light
        }
    }
    val toRemote = { screen = Screen.Remote }
    MaterialTheme(colorScheme = colors) {
        lg?.let { LgPinDialog(it) }
        apple?.takeIf { appleRemoteVisible }?.let { AppleTvKeyboardDialog(it) }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            when {
                screen == Screen.GeneralSettings ->
                    GeneralSettingsScreen(innerPadding, theme, onTheme = {
                        theme = it
                        preferences.edit().putString("theme", it.id).apply()
                    }, layout = layout, onLayout = {
                        layout = it
                        preferences.edit().putString("layout", it.id).apply()
                    }, onBack = toRemote)
                screen == Screen.AddDevice -> AddDeviceScreen(innerPadding, store, discovery, onBack = toRemote, onAdded = toRemote)
                current == null ->
                    EmptyRemoteScreen(
                        innerPadding,
                        onFindDevices = { screen = Screen.AddDevice },
                        onGeneralSettings = { screen = Screen.GeneralSettings },
                    )
                screen == Screen.DeviceSettings -> DeviceSettingsScreen(innerPadding, current, store, lg, apple, onBack = toRemote)
                screen == Screen.Apps && apple != null -> AppleTvAppsScreen(innerPadding, apple, onBack = toRemote)
                else -> {
                    val remote =
                        RemoteActions(
                            devices = devices,
                            current = current,
                            layout = layout,
                            onSelect = { store.select(it.id) },
                            onAddDevice = { screen = Screen.AddDevice },
                            onSettings = { screen = Screen.DeviceSettings },
                            onGeneralSettings = { screen = Screen.GeneralSettings },
                        )
                    if (lg != null) {
                        LgRemote(innerPadding, remote, lg)
                    } else if (apple != null) {
                        AppleTvRemote(innerPadding, remote, apple, appleConnecting, onApps = { screen = Screen.Apps })
                    }
                }
            }
        }
    }
}

/** What every remote shares, whichever kind of device it drives. */
private class RemoteActions(
    val devices: List<SavedDevice>,
    val current: SavedDevice,
    val layout: AppLayout,
    val onSelect: (SavedDevice) -> Unit,
    val onAddDevice: () -> Unit,
    val onSettings: () -> Unit,
    val onGeneralSettings: () -> Unit,
)

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
        onConnect = { if (client.fingerprint.isEmpty()) remote.onSettings() else run { client.connect() } },
        onApps = null,
        onSettings = remote.onSettings,
        onGeneralSettings = remote.onGeneralSettings,
        onPower = { run { client.send(LgProtocol.Action.Wake) } },
        onKey = { key, _ -> key.lg?.let { action -> run { client.send(action) } } },
    )
}

/** The Apple TV session is opened by the shell while this is visible; presses reuse it. */
@Composable
private fun AppleTvRemote(
    padding: PaddingValues,
    remote: RemoteActions,
    client: CompanionClient,
    connecting: Boolean,
    onApps: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status by client.status.collectAsState()
    val paired by client.paired.collectAsState()
    var busy by remember { mutableStateOf(false) }

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
    RemoteScreen(
        contentPadding = padding,
        devices = remote.devices,
        current = remote.current,
        layout = remote.layout,
        ready = paired,
        busy = busy || connecting,
        powerEnabled = paired,
        status = status.message,
        onSelect = remote.onSelect,
        onAddDevice = remote.onAddDevice,
        onConnect = null,
        onApps = onApps,
        onSettings = remote.onSettings,
        onGeneralSettings = remote.onGeneralSettings,
        onPower = { run { client.sleep() } },
        onKey = { key, action -> run { client.press(key.hid, action) } },
    )
}

@Composable
private fun EmptyRemoteScreen(
    contentPadding: PaddingValues,
    onFindDevices: () -> Unit,
    onGeneralSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        EngravedLabel("KAUKOSÄÄDIN")
        Text("No devices added", style = MaterialTheme.typography.headlineSmall)
        Text("Scan your Wi-Fi for TVs and Apple TVs to start using the remote.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onFindDevices) { Text("Find devices") }
        Text("Supports Apple TV and LG webOS TVs", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onGeneralSettings) { Text("General settings") }
    }
}
