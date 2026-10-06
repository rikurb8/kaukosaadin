package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.companion.AppleTvApp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
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
 * The Apple TV session's own rules, checked without a TV: its dispatch refuses a second action while
 * one is in flight instead of queueing it, and its link lives exactly as long as its screen does.
 */
class AppleTvSessionTest {
    @Test
    fun aDispatchWhileOneIsInFlightIsRefusedAndNeverQueued() =
        runBlocking {
            val inFlight = CompletableDeferred<Unit>()
            val session = session(this)
            var presses = 0

            session.run {
                presses++
                inFlight.await()
            }
            yield()
            session.run { presses++ }
            yield()

            assertEquals("the first press is the only one that starts", 1, presses)
            assertTrue("the session is busy while the press is in flight", session.busy.value)

            inFlight.complete(Unit)
            withTimeout(TIMEOUT_MS) { session.busy.first { !it } }
            yield()

            assertEquals("the refused press was dropped, not run later", 1, presses)
            assertFalse(session.busy.value)
        }

    @Test
    fun liveConnectsWhileTheScreenIsVisibleAndDisconnectsWhenItLeaves() =
        runBlocking {
            val connecting = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val session =
                session(
                    this,
                    connect = {
                        events += "connect"
                        connecting.await()
                    },
                    disconnect = { events += "disconnect" },
                )

            val screen = launch { session.live() }
            yield()

            assertEquals(listOf("connect"), events)
            assertTrue("the session is connecting until the link is up", session.connecting.value)

            connecting.complete(Unit)
            withTimeout(TIMEOUT_MS) { session.connecting.first { !it } }

            assertEquals("a connected session stays open", listOf("connect"), events)

            screen.cancelAndJoin()

            assertEquals("leaving the screen closes the link once", listOf("connect", "disconnect"), events)
        }

    private fun session(
        scope: CoroutineScope,
        connect: suspend () -> Unit = {},
        disconnect: suspend () -> Unit = {},
    ) = AppleTvSession(
        scope = scope,
        status = MutableStateFlow(CompanionClient.Result(true, "Connected to Apple TV.")),
        paired = MutableStateFlow(true),
        apps = MutableStateFlow(emptyList<AppleTvApp>()),
        connect = connect,
        disconnect = disconnect,
    )

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
