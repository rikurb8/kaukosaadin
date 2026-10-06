package fi.goodconsulting.kaukosaadin.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import fi.goodconsulting.kaukosaadin.device.companion.AppleTvApp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One Apple TV session, driven by any [CompanionClient]: [live] opens the verified link while its
 * screen is visible and closes it on leaving or backgrounding, and [run] dispatches an action at a
 * time. The client's own state is handed in as [status], [paired] and [apps], and its link as
 * [connect]/[disconnect], so the session holds no client and its rules are checked without a TV.
 *
 * The click handlers pass their one action to [run]; the session never queues, repeats or replays it.
 */
internal class AppleTvSession(
    private val scope: CoroutineScope,
    /** The TV's latest status message. */
    val status: StateFlow<CompanionClient.Result>,
    /** Whether this Apple TV is paired on this phone. */
    val paired: StateFlow<Boolean>,
    /** The apps the TV last reported; empty until an app-list refresh succeeds. */
    val apps: StateFlow<List<AppleTvApp>>,
    private val connect: suspend () -> Unit,
    private val disconnect: suspend () -> Unit,
) {
    private val mutableConnecting = MutableStateFlow(false)

    /** True until [live] has finished its one connect; the screen shows it as busy meanwhile. */
    val connecting: StateFlow<Boolean> = mutableConnecting.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)

    /** True while a dispatched action is in flight; the screen disables its controls meanwhile. */
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    /**
     * Dispatches [block] unless one is already in flight. A call while one is in flight returns at
     * once and its [block] never runs: actions are refused, never queued and never replayed, so a
     * repeated tap cannot become a second press.
     */
    fun run(block: suspend () -> Unit) {
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

    /**
     * Runs for as long as the screen showing this session is visible: opens the verified link once,
     * holds it, and closes it in a `finally` when the caller is cancelled — leaving the screen or
     * backgrounding the app. The next entry connects again; nothing is queued or replayed.
     */
    suspend fun live() {
        try {
            mutableConnecting.value = true
            try {
                connect()
            } finally {
                mutableConnecting.value = false
            }
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { disconnect() }
        }
    }
}

/**
 * Holds one [AppleTvSession] for [client] while [content] is visible: it owns the RESUMED-scoped
 * connect/disconnect, mirrors the TV's on-screen keyboard, and hands [content] the session to render
 * and dispatch through. The same session serves the picker-selected Apple TV or any other saved one.
 */
@Composable
internal fun AppleTvSessionHost(
    client: CompanionClient,
    content: @Composable (AppleTvSession) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session =
        remember(client, scope) {
            AppleTvSession(
                scope = scope,
                status = client.status,
                paired = client.paired,
                apps = client.apps,
                connect = { client.connect() },
                disconnect = { client.disconnect() },
            )
        }
    val activity = LocalActivity.current
    LaunchedEffect(activity, session) {
        if (activity is LifecycleOwner) {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { session.live() }
        }
    }
    AppleTvKeyboardDialog(client)
    content(session)
}
