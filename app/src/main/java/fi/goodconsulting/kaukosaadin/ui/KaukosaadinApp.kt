package fi.goodconsulting.kaukosaadin.ui

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import fi.goodconsulting.kaukosaadin.device.DeviceStore
import fi.goodconsulting.kaukosaadin.device.SavedDevice

/** Where the app is; every screen but the remote returns to it. */
private enum class Screen { Remote, AddDevice, DeviceSettings, GeneralSettings, SuperRemoteSetup }

/**
 * The app shell: the saved devices, the screen router, theme/layout preferences and the empty
 * screen. The selected device's [DeviceIntegration] owns its kind's setup, remote and settings;
 * [DeviceIntegrations] is the register of kinds. The Super remote layout drives [SuperRemoteStore]'s
 * own bindings instead of the selected device.
 */
@Composable
fun KaukosaadinApp() {
    val context = LocalContext.current.applicationContext
    val store = remember { DeviceStore(context) }
    val devices by store.devices.collectAsState()
    val selectedId by store.selectedId.collectAsState()
    val current = DeviceStore.current(devices, selectedId)
    val controls =
        remember(current?.id) {
            current?.let { DeviceIntegrations.of(it.kind).controls(context, it) }
        }
    val preferences = remember { context.getSharedPreferences("general_settings", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(AppTheme.fromId(preferences.getString("theme", null))) }
    var layout by remember { mutableStateOf(AppLayout.fromId(preferences.getString("layout", null))) }
    var screen by rememberSaveable { mutableStateOf(Screen.Remote) }

    val colors = palette(theme)
    SystemBars(colors)
    val toRemote = { screen = Screen.Remote }
    MaterialTheme(colorScheme = colors) {
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
                screen == Screen.AddDevice -> AddDeviceScreen(innerPadding, store, onBack = toRemote, onAdded = toRemote)
                current == null || controls == null ->
                    EmptyRemoteScreen(
                        innerPadding,
                        onFindDevices = { screen = Screen.AddDevice },
                        onGeneralSettings = { screen = Screen.GeneralSettings },
                    )
                screen == Screen.DeviceSettings ->
                    DeviceSettingsScreen(innerPadding, current, store, controls, onBack = toRemote)
                layout == AppLayout.SuperRemote ->
                    SuperRemoteRoute(innerPadding, devices, setup = screen == Screen.SuperRemoteSetup) { screen = it }
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
                    controls.Remote(innerPadding, remote)
                }
            }
        }
    }
}

/** The palette for [theme]; Classic follows the system light/dark setting. */
@Composable
private fun palette(theme: AppTheme): ColorScheme =
    when (theme) {
        AppTheme.Classic -> if (isSystemInDarkTheme()) DarkColors else LightColors
        AppTheme.HackerMan -> HackerManColors
    }

/** Tints the status and navigation bars so their icons stay legible on the palette. */
@Composable
private fun SystemBars(colors: ColorScheme) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    SideEffect {
        val bars = WindowCompat.getInsetsController(window, view)
        val light = colors.background.luminance() > 0.5f
        bars.isAppearanceLightStatusBars = light
        bars.isAppearanceLightNavigationBars = light
    }
}

/**
 * The Super remote layout: one [SuperRemoteStore] shared by its two screens, so setup writes the same
 * bindings the Super remote reads and a choice shows up there without a restart. [setup] picks the
 * setup screen rather than the remote; its bindings are kept in `super_remote` storage apart from the
 * saved devices, so routing here changes neither the picker's selection nor what the remote binds.
 */
@Composable
private fun SuperRemoteRoute(
    padding: PaddingValues,
    devices: List<SavedDevice>,
    setup: Boolean,
    onScreen: (Screen) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val store = remember { SuperRemoteStore(context) }
    val bindings by store.bindings.collectAsState()
    if (setup) {
        SuperRemoteSetupScreen(
            padding = padding,
            devices = devices,
            bindings = bindings,
            setup = remember(store) { SuperRemoteSetup({ store.bindings.value }, store::write) },
            onAddDevice = { onScreen(Screen.AddDevice) },
            onBack = { onScreen(Screen.Remote) },
        )
    } else {
        SuperRemoteScreen(
            padding = padding,
            devices = devices,
            bindings = bindings,
            onSetup = { onScreen(Screen.SuperRemoteSetup) },
            onDeviceSettings = { onScreen(Screen.DeviceSettings) },
            onGeneralSettings = { onScreen(Screen.GeneralSettings) },
        )
    }
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
        Text("Scan your Wi-Fi for TVs, Apple TVs and Hue Bridges to start using the remote.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onFindDevices) { Text("Find devices") }
        Text("Supports Apple TV, LG webOS TVs and Hue Bridges", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onGeneralSettings) { Text("General settings") }
    }
}
