package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.AppleTvApp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.companion.HidCommand
import fi.goodconsulting.kaukosaadin.device.companion.PressAction
import fi.goodconsulting.kaukosaadin.device.hue.HueCommandTarget
import fi.goodconsulting.kaukosaadin.device.hue.HueResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Super remote's sections share one screen but not their readiness, busy or failure: each is
 * handed its own state holder and its own client, so an unavailable bridge leaves the Apple TV's
 * presses and the shortcut row's launches usable, an unavailable Apple TV leaves the lighting
 * presets usable, and neither section's busy flag or failure text reaches the other.
 *
 * Checked at the seam [SuperRemoteScreen] composes the sections from — the bindings resolved against
 * the saved devices, then each section's own state holder (the section tickets' seams) — so no
 * Compose test framework is needed. [RecordingHueLighting], from the lighting section's own checks,
 * stands in for the configured bridge.
 */
class SuperRemoteSectionIndependenceTest {
    @Test fun aFailedBridgeLeavesTheAppleTvPressAndTheShortcutLaunchUsable() =
        runBlocking {
            val bridge = RecordingHueLighting()
            bridge.onWithBrightnessCommand = { _, _ -> HueResult.Failure(BRIDGE_FAILURE) }
            val presets = LightingController(bridge)
            presets.applyPreset(checkNotNull(presetTarget(lighting, bridge)), LightingPreset.Bright)
            assertEquals("the failure is the lighting section's own", BRIDGE_FAILURE, presets.state.value.failure)

            val appleTv = FakeAppleTv()
            val session = session(this, appleTv::connect)
            dispatchAppleTvPress(session, appleTv::press, RemoteKey.Home, PressAction.Tap)
            withTimeout(TIMEOUT_MS) { session.busy.first { !it } }

            val launched = mutableListOf<AppleTvApp>()
            val row = SuperRemoteAppLaunch(this, MutableStateFlow(listOf(YOUTUBE)), {}, { launched += it })
            row.launch(YOUTUBE)
            withTimeout(TIMEOUT_MS) { row.busy.first { !it } }

            assertEquals("the failed bridge never stops a press", listOf(HidCommand.Home to PressAction.Tap), appleTv.sent)
            assertEquals("...nor a shortcut launch", listOf(YOUTUBE.bundleId), launched.map { it.bundleId })
            assertEquals("the bridge's failure text never reached the TV's status", TV_STATUS, session.status.value.message)
            assertFalse(session.busy.value)
            assertEquals("and the section recovered: nothing is left in flight", emptySet<String>(), presets.state.value.busyTargets)
        }

    @Test fun anUnavailableAppleTvLeavesTheLightingPresetUsable() =
        runBlocking {
            // The bound Apple TV was forgotten, and the session that would drive it says it is not paired.
            val targets = configured.copy(appleTvDeviceId = null).resolve(listOf(APPLE_TV, BRIDGE))
            assertNull("the section has no Apple TV to drive", targets.appleTv)
            val session = session(this, status = TV_FAILURE, paired = false)
            assertFalse(
                "the TV's own keys are closed by the TV's own readiness",
                appleTvControlsEnabled(session.paired.value, session.connecting.value, session.busy.value),
            )

            // The lighting section keeps exactly its own bridge and target, and still commands it once.
            val bridge = RecordingHueLighting()
            val target = checkNotNull(presetTarget(targets.lighting, bridge))
            assertEquals(TARGET, target)
            val presets = LightingController(bridge)
            presets.applyPreset(target, LightingPreset.Bright)

            assertEquals(listOf(TARGET to 100), bridge.onWithBrightness)
            assertNull("the TV's readiness left the lighting section no failure", presets.state.value.failure)
            assertEquals(emptySet<String>(), presets.state.value.busyTargets)
            // The section is not merely unfailed: it still takes the operator's next tap.
            presets.applyPreset(target, LightingPreset.Off)
            assertEquals(listOf(TARGET to false), bridge.onOff)
            assertEquals("and the TV's status is still the TV's", TV_FAILURE, session.status.value.message)
        }

    @Test fun eachSectionsBusyAndFailureStayInItsOwnSection() =
        runBlocking {
            val pressInFlight = CompletableDeferred<Unit>()
            val presetInFlight = CompletableDeferred<Unit>()
            val appleTv = FakeAppleTv { pressInFlight.await() }
            val session = session(this, appleTv::connect)
            val bridge = RecordingHueLighting()
            bridge.onWithBrightnessCommand = { _, _ ->
                presetInFlight.await()
                HueResult.Failure(BRIDGE_FAILURE)
            }
            val presets = LightingController(bridge)
            val target = checkNotNull(presetTarget(lighting, bridge))

            // One tap on a key and one on a preset, both in flight at once.
            dispatchAppleTvPress(session, appleTv::press, RemoteKey.Home, PressAction.Tap)
            val applied = launch { presets.applyPreset(target, LightingPreset.Bright) }
            withTimeout(TIMEOUT_MS) { presets.state.first { target.id in it.busyTargets } }

            assertTrue("the TV is busy with its own press", session.busy.value)
            assertEquals("the lighting section is busy with its own preset", setOf(target.id), presets.state.value.busyTargets)
            assertNull("the TV's press is not a lighting failure", presets.state.value.failure)

            // The press finishes while the preset is still in flight: the preset's busy survives it.
            pressInFlight.complete(Unit)
            withTimeout(TIMEOUT_MS) { session.busy.first { !it } }
            assertEquals(
                "one section's action finishing does not clear the other's busy state",
                setOf(target.id),
                presets.state.value.busyTargets,
            )

            // The preset then fails: the failure is the lighting section's, and the TV keeps its own state.
            presetInFlight.complete(Unit)
            applied.join()

            assertEquals(BRIDGE_FAILURE, presets.state.value.failure)
            assertEquals(emptySet<String>(), presets.state.value.busyTargets)
            assertEquals("the bridge's failure never reached the TV's status", TV_STATUS, session.status.value.message)
            assertFalse(session.busy.value)
            assertEquals(PRESSES, appleTv.sent)
            assertEquals(COMMANDS, bridge.onWithBrightness)
        }

    @Test fun aShortcutLaunchAndALightingPresetEachReachOnlyTheirOwnDevice() =
        runBlocking {
            val launched = mutableListOf<AppleTvApp>()
            val row = SuperRemoteAppLaunch(this, MutableStateFlow(listOf(YOUTUBE)), {}, { launched += it })
            val bridge = RecordingHueLighting()
            val presets = LightingController(bridge)

            row.launch(YOUTUBE)
            withTimeout(TIMEOUT_MS) { row.busy.first { !it } }

            assertEquals("opening an app launches exactly that app", listOf(YOUTUBE.bundleId), launched.map { it.bundleId })
            assertTrue("...and commands no light", bridge.onWithBrightness.isEmpty())

            presets.applyPreset(checkNotNull(presetTarget(lighting, bridge)), LightingPreset.Bright)

            assertEquals("applying a preset commands exactly the configured target", COMMANDS, bridge.onWithBrightness)
            assertEquals("...and launches nothing", listOf(YOUTUBE.bundleId), launched.map { it.bundleId })
            assertTrue(bridge.onOff.isEmpty())
        }

    /** The session the Apple TV section builds for its configured Apple TV, with the TV's own state handed in. */
    private fun session(
        scope: CoroutineScope,
        connect: suspend () -> Unit = {},
        status: String = TV_STATUS,
        paired: Boolean = true,
    ) = AppleTvSession(
        scope = scope,
        status = MutableStateFlow(CompanionClient.Result(true, status)),
        paired = MutableStateFlow(paired),
        apps = MutableStateFlow(emptyList<AppleTvApp>()),
        connect = connect,
        disconnect = {},
    )

    /** Stands in for the configured Apple TV's client: it takes presses and can hold one in flight. */
    private class FakeAppleTv(
        private val onPress: suspend () -> Unit = {},
    ) {
        val sent = mutableListOf<Pair<HidCommand, PressAction>>()

        suspend fun connect() = Unit

        suspend fun press(
            command: HidCommand,
            action: PressAction,
        ) {
            sent += command to action
            onPress()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val TV_STATUS = "Connected to Apple TV."
        const val TV_FAILURE = "Could not reach the Apple TV. Check that it is on and on this network."
        const val BRIDGE_FAILURE = "Hue bridge unreachable. Check the Wi-Fi/LAN and that the bridge is on."

        val APPLE_TV = SavedDevice("atv-1", DeviceKind.AppleTv, "Olohuone", "192.168.1.30")
        val BRIDGE = SavedDevice("hue-1", DeviceKind.Hue, "Bridge", "192.168.1.40")
        val YOUTUBE = AppleTvApp("com.google.ios.youtube", "YouTube")
        val TARGET = HueCommandTarget.Group("grouped-1")
        val COMMANDS = listOf(TARGET to 100)
        val PRESSES = listOf(HidCommand.Home to PressAction.Tap)
        val lighting = SuperRemoteLighting(BRIDGE, SuperRemoteLightTarget(TARGET.id, "Living room"))
        val configured =
            SuperRemoteBindings(
                appleTvDeviceId = APPLE_TV.id,
                hueDeviceId = BRIDGE.id,
                hueTargetId = TARGET.id,
                hueTargetName = "Living room",
                shortcuts = listOf(YOUTUBE),
            )
    }
}
