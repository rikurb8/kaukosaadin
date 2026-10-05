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
import fi.goodconsulting.kaukosaadin.device.LgClient
import fi.goodconsulting.kaukosaadin.device.LgProtocol
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.companion.CompanionDiscovery
import fi.goodconsulting.kaukosaadin.device.companion.HidCommand
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The saved device the remote drives; each keeps its own pairing and status. */
internal enum class Target(
    val label: String,
    val annunciator: String,
    val platform: String,
) {
    Lg("LG TV", "TV", "WEBOS"),
    AppleTv("Apple TV", "ATV", "TVOS"),
}

/** Remote keys and the command each target sends; LG has no Home or Play/Pause key. */
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

// ponytail: the shell owns both clients, saved-target fallback and routing in one place;
// split into state holders when a third target or screen lands.

/**
 * One LG client and one Apple TV client share setup, pairing and readiness across
 * the screens; the remote drives whichever saved device is the selected target.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun KaukosaadinApp() {
    val context = LocalContext.current.applicationContext
    val client = remember { LgClient(context) }
    val apple = remember { CompanionClient(context) }
    val discovery = remember { CompanionDiscovery(context) }
    val scope = rememberCoroutineScope()
    val status by client.status.collectAsState()
    val ready by client.ready.collectAsState()
    val appleStatus by apple.status.collectAsState()
    val applePaired by apple.paired.collectAsState()
    val preferences = remember { context.getSharedPreferences("general_settings", android.content.Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(AppTheme.fromId(preferences.getString("theme", null))) }
    var layout by remember { mutableStateOf(AppLayout.fromId(preferences.getString("layout", null))) }
    var generalSettings by rememberSaveable { mutableStateOf(false) }
    var lgSettings by remember { mutableStateOf(false) }
    var appleSettings by remember { mutableStateOf(false) }
    var appleApps by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(Target.Lg) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LgClient.Result?>(null) }
    var appleConnecting by remember { mutableStateOf(false) }

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
    val lgSaved = client.host.isNotEmpty()
    // Fall back to whichever device is actually saved, e.g. after removing the other.
    val target =
        when {
            selected == Target.Lg && !lgSaved && applePaired -> Target.AppleTv
            selected == Target.AppleTv && !applePaired && lgSaved -> Target.Lg
            else -> selected
        }

    val activity = LocalActivity.current
    // The Apple session stays open while the apps screen is shown: it needs the TV to list and launch.
    val appleRemoteVisible = target == Target.AppleTv && applePaired && !generalSettings && !lgSettings && !appleSettings
    val remoteVisible by rememberUpdatedState(appleRemoteVisible)
    LaunchedEffect(activity) {
        snapshotFlow { remoteVisible }.collectLatest { visible ->
            if (visible && activity is LifecycleOwner) {
                activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    try {
                        appleConnecting = true
                        try {
                            apple.connect()
                        } finally {
                            appleConnecting = false
                        }
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { apple.disconnect() }
                    }
                }
            }
        }
    }

    fun openSettings(to: Target) {
        selected = to
        if (to == Target.Lg) lgSettings = true else appleSettings = true
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
    MaterialTheme(colorScheme = colors) {
        LgPinDialog(client)
        AppleTvPinDialog(apple)
        if (appleRemoteVisible) AppleTvKeyboardDialog(apple)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            if (generalSettings) {
                GeneralSettingsScreen(innerPadding, theme, onTheme = {
                    theme = it
                    preferences.edit().putString("theme", it.id).apply()
                }, layout = layout, onLayout = {
                    layout = it
                    preferences.edit().putString("layout", it.id).apply()
                }, onBack = { generalSettings = false })
            } else if (lgSettings) {
                LgConnectionScreen(innerPadding, client) {
                    result = null
                    lgSettings = false
                }
            } else if (appleSettings) {
                AppleTvSetupScreen(innerPadding, apple, discovery) {
                    appleSettings = false
                }
            } else if (appleApps) {
                AppleTvAppsScreen(innerPadding, apple) { appleApps = false }
            } else if (!lgSaved && !applePaired) {
                EmptyRemoteScreen(innerPadding, onAdd = ::openSettings, onGeneralSettings = { generalSettings = true })
            } else if (target == Target.Lg) {
                RemoteScreen(
                    contentPadding = innerPadding,
                    target = target,
                    layout = layout,
                    tvName = client.name,
                    ready = ready,
                    busy = busy,
                    wakeEnabled = client.mac.isNotEmpty() && client.broadcast.isNotEmpty() && client.fingerprint.isNotEmpty(),
                    status = (if (busy) status else result ?: status).message,
                    onTarget = { if (it == Target.Lg || applePaired) selected = it else openSettings(it) },
                    onConnect = {
                        if (client.host.isEmpty() || client.fingerprint.isEmpty()) {
                            lgSettings = true
                        } else {
                            run { result = client.connect() }
                        }
                    },
                    onSettings = { lgSettings = true },
                    onApps = null,
                    onGeneralSettings = { generalSettings = true },
                    onWake = { run { result = client.send(LgProtocol.Action.Wake) } },
                    onKey = { key, _ -> key.lg?.let { action -> run { result = client.send(action) } } },
                )
            } else {
                RemoteScreen(
                    contentPadding = innerPadding,
                    target = target,
                    layout = layout,
                    tvName = apple.name,
                    ready = applePaired,
                    busy = busy || appleConnecting,
                    wakeEnabled = false,
                    status = appleStatus.message,
                    onTarget = { if (it == Target.AppleTv || lgSaved) selected = it else openSettings(it) },
                    onConnect = null,
                    onApps = { appleApps = true },
                    onSettings = { appleSettings = true },
                    onGeneralSettings = { generalSettings = true },
                    onWake = {},
                    onKey = { key, action -> run { apple.press(key.hid, action) } },
                )
            }
        }
    }
}

@Composable
private fun EmptyRemoteScreen(
    contentPadding: PaddingValues,
    onAdd: (Target) -> Unit,
    onGeneralSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        EngravedLabel("KAUKOSÄÄDIN")
        Text("No TVs added", style = MaterialTheme.typography.headlineSmall)
        Text("Add your TV to start using the remote.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { onAdd(Target.Lg) }) { Text("Add LG TV") }
        Button(onClick = { onAdd(Target.AppleTv) }) { Text("Add Apple TV") }
        Text("Supports LG webOS TVs and Apple TV on your Wi-Fi", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onGeneralSettings) { Text("General settings") }
    }
}
