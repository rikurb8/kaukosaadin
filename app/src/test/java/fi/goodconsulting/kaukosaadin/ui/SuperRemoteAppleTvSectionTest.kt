package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.companion.AppleTvApp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.companion.HidCommand
import fi.goodconsulting.kaukosaadin.device.companion.PressAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Super remote's Apple TV controls: a press reaches the client built for the configured Apple TV
 * with the key's own Companion command and gesture action, a press while one is in flight is refused
 * rather than queued, reconnect restores readiness without repeating a press, and a forgotten
 * configured Apple TV sends nothing.
 */
class SuperRemoteAppleTvSectionTest {
    @Test
    fun aPressSendsTheKeysCompanionCommandToTheConfiguredAppleTv() =
        runBlocking {
            val sent = mutableListOf<Pair<HidCommand, PressAction>>()
            val session = session(this)

            // Back is the Siri Remote's Menu; Home and Play/Pause have commands of their own.
            dispatch(session, sent, RemoteKey.Back, PressAction.Tap)
            dispatch(session, sent, RemoteKey.Home, PressAction.Tap)
            dispatch(session, sent, RemoteKey.PlayPause, PressAction.Tap)

            assertEquals(
                listOf(
                    HidCommand.Menu to PressAction.Tap,
                    HidCommand.Home to PressAction.Tap,
                    HidCommand.PlayPause to PressAction.Tap,
                ),
                sent,
            )
        }

    @Test
    fun backAndHomeGesturesReachTheTvUnchanged() =
        runBlocking {
            val sent = mutableListOf<Pair<HidCommand, PressAction>>()
            val session = session(this)

            dispatch(session, sent, RemoteKey.Back, PressAction.DoubleTap)
            dispatch(session, sent, RemoteKey.Home, PressAction.Hold)

            assertEquals(
                listOf(HidCommand.Menu to PressAction.DoubleTap, HidCommand.Home to PressAction.Hold),
                sent,
            )
        }

    @Test
    fun aPressWhileOneIsInFlightIsRefusedAndNeverQueued() =
        runBlocking {
            val inFlight = CompletableDeferred<Unit>()
            val session = session(this)
            var presses = 0
            val send: suspend (HidCommand, PressAction) -> Unit = { _, _ ->
                presses++
                inFlight.await()
            }

            dispatchAppleTvPress(session, send, RemoteKey.Home, PressAction.Tap)
            yield()
            dispatchAppleTvPress(session, send, RemoteKey.Home, PressAction.Tap)
            yield()

            assertEquals("the first press is the only one that reaches the TV", 1, presses)
            assertTrue("the session is busy while the press is in flight", session.busy.value)

            inFlight.complete(Unit)
            withTimeout(TIMEOUT_MS) { session.busy.first { !it } }
            yield()

            assertEquals("the refused press was dropped, not run later", 1, presses)
            assertFalse(session.busy.value)
        }

    @Test
    fun reconnectRestoresReadinessAndNeverRepeatsAPress() =
        runBlocking {
            val fake = FakeAppleTv()
            val session = session(this)

            // A press went out; the link later dropped. The operator reconnects.
            dispatchAppleTvPress(session, fake::press, RemoteKey.Home, PressAction.Tap)
            withTimeout(TIMEOUT_MS) { session.busy.first { !it } }
            dispatchAppleTvReconnect(session, fake::connect)
            withTimeout(TIMEOUT_MS) { session.busy.first { !it } }
            yield()

            assertEquals("reconnect re-opens the link once", 1, fake.connects)
            assertEquals(
                "reconnect sends no press and repeats nothing",
                listOf(HidCommand.Home to PressAction.Tap),
                fake.sent,
            )
        }

    @Test
    fun aForgottenConfiguredAppleTvSendsNothing() =
        runBlocking {
            var presses = 0
            val send: suspend (HidCommand, PressAction) -> Unit = { _, _ -> presses++ }

            // No configured Apple TV means no session: the section drives nothing.
            dispatchAppleTvPress(session = null, send = send, key = RemoteKey.Home, action = PressAction.Tap)
            yield()

            assertEquals("a forgotten Apple TV is never commanded", 0, presses)
        }

    @Test
    fun controlsAreEnabledOnlyWhilePairedAndIdle() {
        assertTrue(appleTvControlsEnabled(paired = true, connecting = false, busy = false))
        assertFalse("connecting disables the controls", appleTvControlsEnabled(paired = true, connecting = true, busy = false))
        assertFalse("sending disables the controls", appleTvControlsEnabled(paired = true, connecting = false, busy = true))
        assertFalse("an unpaired Apple TV disables the controls", appleTvControlsEnabled(paired = false, connecting = false, busy = false))
    }

    private suspend fun dispatch(
        session: AppleTvSession,
        sent: MutableList<Pair<HidCommand, PressAction>>,
        key: RemoteKey,
        action: PressAction,
    ) {
        dispatchAppleTvPress(session, { command, press -> sent += command to press }, key, action)
        withTimeout(TIMEOUT_MS) { session.busy.first { !it } }
    }

    private fun session(scope: CoroutineScope) =
        AppleTvSession(
            scope = scope,
            status = MutableStateFlow(CompanionClient.Result(true, "Connected to Apple TV.")),
            paired = MutableStateFlow(true),
            apps = MutableStateFlow(emptyList<AppleTvApp>()),
            connect = {},
            disconnect = {},
        )

    /** Stands in for the configured Apple TV's client: records the link opens and the presses it takes. */
    private class FakeAppleTv {
        var connects = 0
        val sent = mutableListOf<Pair<HidCommand, PressAction>>()

        suspend fun connect() {
            connects++
        }

        suspend fun press(
            command: HidCommand,
            action: PressAction,
        ) {
            sent += command to action
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
