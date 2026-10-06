package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fi.goodconsulting.kaukosaadin.device.companion.AppleTvApp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Super remote's app shortcuts, in one row at the top. [shortcuts] and [appleTv] are the
 * configured Apple TV's, resolved by [SuperRemoteScreen] from the Super remote's own bindings, so
 * the row never follows the device picker. A row that is not set up yet, or whose Apple TV is gone,
 * asks for reselection instead of asking any TV to open something.
 */
@Composable
internal fun SuperRemoteAppsRow(
    shortcuts: List<AppleTvApp>,
    appleTv: CompanionClient?,
    onSetup: () -> Unit,
) {
    Text("Apps", style = MaterialTheme.typography.titleMedium)
    when {
        appleTv == null -> {
            Text("No Apple TV chosen yet, so there is nothing to launch on.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onSetup) { Text("Choose Apple TV") }
        }
        shortcuts.isEmpty() -> {
            Text("No app shortcuts chosen yet. Pick them from the Apple TV's own app list.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onSetup) { Text("Choose apps") }
        }
        else -> ShortcutButtons(shortcuts, appleTv, onSetup)
    }
}

/**
 * The row's buttons: one per shortcut, each asking [appleTv] to open that shortcut's own app. A
 * shortcut the TV no longer reports says so and routes to [onSetup] instead of launching anything.
 * The buttons are disabled while a tap is in flight, so a repeated tap is dropped instead of queueing
 * a second launch. The TV's app report is refreshed when the row appears and on every return to it;
 * a return refreshes names only and never re-sends a launch.
 */
@Composable
private fun ShortcutButtons(
    shortcuts: List<AppleTvApp>,
    appleTv: CompanionClient,
    onSetup: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val row =
        remember(appleTv, scope) {
            SuperRemoteAppLaunch(
                scope = scope,
                apps = appleTv.apps,
                refreshApps = { appleTv.appList() },
                launchApp = { appleTv.launchApp(it) },
            )
        }
    val apps by appleTv.apps.collectAsState()
    val busy by row.busy.collectAsState()
    val activity = LocalActivity.current

    LaunchedEffect(activity, row) {
        if (activity is LifecycleOwner) {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { row.refresh() }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        shortcuts.forEach { stored ->
            val shortcut = resolveShortcut(stored, apps)
            if (shortcut.launchable) {
                Button(enabled = !busy, onClick = { row.launch(stored) }) { Text(shortcut.app.name) }
            } else {
                TextButton(enabled = !busy, onClick = onSetup) { Text("${shortcut.app.name} — not on the Apple TV; choose again") }
            }
        }
    }
}

/**
 * One stored shortcut as the row shows it: [app] carries the name to label the button with and the
 * bundle id to launch, and [launchable] says whether the TV still offers that app.
 */
internal data class SuperRemoteShortcut(
    val app: AppleTvApp,
    val launchable: Boolean,
)

/**
 * Resolves [stored] against the apps the TV last [reported]: the TV's current name for that bundle id
 * once it reports one, the stored name while no report has arrived — an unreported app is not a
 * missing one — and "not launchable" once the report has arrived without that bundle id. A different
 * app is never substituted for a missing one.
 */
internal fun resolveShortcut(
    stored: AppleTvApp,
    reported: List<AppleTvApp>,
): SuperRemoteShortcut {
    val current = reported.firstOrNull { it.bundleId == stored.bundleId }
    return when {
        current != null -> SuperRemoteShortcut(current, true)
        reported.isEmpty() -> SuperRemoteShortcut(stored, true)
        else -> SuperRemoteShortcut(stored, false)
    }
}

/**
 * The row's own client work, in its own one-action-at-a-time shape: [refresh] reads the TV's current
 * app report, [launch] asks the TV to open the app a stored shortcut was bound to, and a call while
 * one is in flight is refused rather than queued. It holds no client — the TV's report and its two
 * calls are handed in — so the row's rules are checked without an Apple TV.
 */
internal class SuperRemoteAppLaunch(
    private val scope: CoroutineScope,
    /** The apps the TV last reported; empty until [refresh] succeeds. */
    val apps: StateFlow<List<AppleTvApp>>,
    private val refreshApps: suspend () -> Unit,
    private val launchApp: suspend (AppleTvApp) -> Unit,
) {
    private val mutableBusy = MutableStateFlow(false)

    /** True while [refresh] or a [launch] is in flight; the row disables its buttons meanwhile. */
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    /** Reads the TV's current app report. Never sends a launch. */
    fun refresh() = run { refreshApps() }

    /** Asks the TV to open [stored]'s app: the bundle id is the stored one, never a substitute. */
    fun launch(stored: AppleTvApp) {
        val shortcut = resolveShortcut(stored, apps.value)
        if (shortcut.launchable) run { launchApp(shortcut.app) }
    }

    private fun run(block: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        scope.launch {
            try {
                block()
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
