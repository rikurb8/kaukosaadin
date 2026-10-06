package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.companion.AppleTvApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Super remote's app shortcut row's own rules, checked without an Apple TV: a tap asks the
 * configured TV to open exactly the bundle id its shortcut was bound to, a shortcut the TV no longer
 * reports sends nothing, and a second tap while one is in flight is dropped rather than queued.
 */
class SuperRemoteAppsRowTest {
    private val youtube = AppleTvApp("com.google.ios.youtube", "YouTube")
    private val areena = AppleTvApp("fi.yle.areena", "Yle Areena")

    @Test fun aTapLaunchesExactlyTheBoundBundleIdOnTheConfiguredAppleTv() =
        runBlocking {
            val launched = mutableListOf<AppleTvApp>()
            val row = row(this, reported = listOf(youtube, areena), launchApp = { launched += it })

            row.launch(areena)
            withTimeout(TIMEOUT_MS) { row.busy.first { !it } }

            assertEquals(listOf("fi.yle.areena"), launched.map { it.bundleId })
        }

    @Test fun aShortcutTheTvNoLongerReportsSendsNoLaunch() =
        runBlocking {
            val launched = mutableListOf<AppleTvApp>()
            val row = row(this, reported = listOf(areena), launchApp = { launched += it })

            row.launch(youtube)
            yield()

            assertEquals(emptyList<AppleTvApp>(), launched)
            assertFalse("nothing was sent, so the row is not busy", row.busy.value)
        }

    @Test fun labelsFollowTheTvsReportWithoutDeclaringAMissingApp() {
        val renamed = AppleTvApp(areena.bundleId, "Yle Areena HD")

        // The TV's report is the current name for that bundle id, so a rename on the TV follows.
        assertEquals(SuperRemoteShortcut(renamed, true), resolveShortcut(areena, listOf(renamed, youtube)))
        // No report yet: the stored name stands, and the app is not declared missing.
        assertEquals(SuperRemoteShortcut(youtube, true), resolveShortcut(youtube, emptyList()))
        // The report arrived without that bundle id: reselection, and never a substitute app.
        assertEquals(SuperRemoteShortcut(youtube, false), resolveShortcut(youtube, listOf(areena)))
    }

    @Test fun aSecondTapWhileOneIsInFlightIsDroppedAndNeverQueued() =
        runBlocking {
            val inFlight = CompletableDeferred<Unit>()
            val launched = mutableListOf<AppleTvApp>()
            val row =
                row(this, reported = listOf(youtube, areena)) {
                    launched += it
                    inFlight.await()
                }

            row.launch(youtube)
            yield()
            row.launch(areena)
            yield()

            assertEquals("the first tap is the only one that starts", listOf("com.google.ios.youtube"), launched.map { it.bundleId })
            assertTrue("the row is busy while a launch is in flight", row.busy.value)

            inFlight.complete(Unit)
            withTimeout(TIMEOUT_MS) { row.busy.first { !it } }
            yield()

            assertEquals("the refused tap was dropped, not run later", listOf("com.google.ios.youtube"), launched.map { it.bundleId })
            assertFalse(row.busy.value)
        }

    @Test fun returningToTheRowRefreshesTheReportAndReplaysNoLaunch() =
        runBlocking {
            var refreshes = 0
            val launched = mutableListOf<AppleTvApp>()
            val row = row(this, reported = listOf(youtube), refresh = { refreshes++ }, launchApp = { launched += it })

            row.launch(youtube)
            withTimeout(TIMEOUT_MS) { row.busy.first { !it } }

            // What the row does on every return to it: re-read the TV's report, send nothing.
            row.refresh()
            withTimeout(TIMEOUT_MS) { row.busy.first { !it } }

            assertEquals(1, refreshes)
            assertEquals(listOf("com.google.ios.youtube"), launched.map { it.bundleId })
        }

    @Test fun aRefreshAwaitsItsReadSoLeavingTheScreenCancelsIt() =
        runBlocking {
            val inFlight = CompletableDeferred<Unit>()
            var completed = false
            val row =
                row(this, refresh = {
                    inFlight.await()
                    completed = true
                })

            // What the RESUMED-scoped effect does on entry: start the read and await it in place.
            val refresh = launch { row.refresh() }
            yield()

            assertTrue("the read keeps the row busy until it finishes", row.busy.value)
            assertFalse("the refresh does not return while the read is in flight", completed)

            // Leaving RESUMED cancels the effect, so the in-flight read is cancelled with it.
            refresh.cancelAndJoin()

            assertFalse("a cancelled read leaves the row idle", row.busy.value)
            assertFalse("the cancelled read never completed", completed)
        }

    /** The row as the composable builds it: the TV's report, its two calls, and the row's own scope. */
    private fun row(
        scope: CoroutineScope,
        reported: List<AppleTvApp> = emptyList(),
        refresh: suspend () -> Unit = {},
        launchApp: suspend (AppleTvApp) -> Unit = {},
    ) = SuperRemoteAppLaunch(
        scope = scope,
        apps = MutableStateFlow(reported),
        refreshApps = refresh,
        launchApp = launchApp,
    )

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
