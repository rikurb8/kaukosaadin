package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.DeviceKind
import fi.goodconsulting.kaukosaadin.device.SavedDevice
import fi.goodconsulting.kaukosaadin.device.companion.AppleTvApp
import fi.goodconsulting.kaukosaadin.device.companion.CompanionClient
import fi.goodconsulting.kaukosaadin.device.hue.HueCommandTarget
import fi.goodconsulting.kaukosaadin.device.hue.HueConnectionState
import fi.goodconsulting.kaukosaadin.device.hue.HueEvent
import fi.goodconsulting.kaukosaadin.device.hue.HueGroup
import fi.goodconsulting.kaukosaadin.device.hue.HueGroupedLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import fi.goodconsulting.kaukosaadin.device.hue.HueResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Super remote's lighting presets, checked at the controller seam the section drives: one tap is
 * exactly one command to the configured room's or zone's grouped light, a repeated tap while one is
 * in flight is dropped, and a bridge failure stays in this section. The exact request bodies are
 * pinned by HueApiTest; this checks which command the section sends for which preset.
 */
class SuperRemoteLightingSectionTest {
    @Test fun brightTurnsTheTargetOnAt100InOneCombinedCommand() =
        runBlocking {
            val fake = RecordingHueLighting()
            val controller = LightingController(fake)

            controller.applyPreset(TARGET, LightingPreset.Bright)

            assertEquals(listOf(TARGET to 100), fake.onWithBrightness)
            assertTrue("Bright is one command, not an on then a brightness", fake.onOff.isEmpty())
            assertTrue(fake.brightness.isEmpty())
        }

    @Test fun dimTurnsAnOffTargetOnAt20InOneCombinedCommand() =
        runBlocking {
            val fake = RecordingHueLighting(groupedLightsResult = { HueResult.Ok(listOf(OFF_GROUP)) })
            val controller = LightingController(fake)
            controller.load()
            val reported = controller.state.value.groupedLights
            assertFalse("the target is off before the tap", reported.single().on)

            controller.applyPreset(TARGET, LightingPreset.Dim)

            assertEquals(listOf(TARGET to 20), fake.onWithBrightness)
            assertTrue(fake.onOff.isEmpty())
        }

    @Test fun offTurnsTheTargetOffWithTheOnOnlyCommand() =
        runBlocking {
            val fake = RecordingHueLighting(groupedLightsResult = { HueResult.Ok(listOf(ON_GROUP)) })
            val controller = LightingController(fake)
            controller.load()

            controller.applyPreset(TARGET, LightingPreset.Off)

            assertEquals(listOf(TARGET to false), fake.onOff)
            assertTrue(fake.onWithBrightness.isEmpty())
        }

    @Test fun aSecondTapWhileTheFirstIsInFlightIsDropped() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fake = RecordingHueLighting()
            fake.onWithBrightnessCommand = { _, _ ->
                gate.await()
                HueResult.Ok(Unit)
            }
            val controller = LightingController(fake)

            val first = launch { controller.applyPreset(TARGET, LightingPreset.Bright) }
            withTimeout(TIMEOUT_MS) { controller.state.first { TARGET.id in it.busyTargets } }

            val second = launch { controller.applyPreset(TARGET, LightingPreset.Dim) }
            withTimeout(TIMEOUT_MS) { while (second.isActive) yield() }

            assertEquals(listOf(TARGET to 100), fake.onWithBrightness)
            gate.complete(Unit)
            first.join()
        }

    @Test fun aFailedPresetSurfacesLocallyAndLeavesTheSectionReadyForTheNextTap() =
        runBlocking {
            val fake = RecordingHueLighting()
            fake.onWithBrightnessCommand = { _, _ -> HueResult.Failure(BRIDGE_FAILURE) }
            val controller = LightingController(fake)

            controller.applyPreset(TARGET, LightingPreset.Bright)

            val state = controller.state.value
            assertEquals(BRIDGE_FAILURE, state.failure)
            assertTrue("a failed preset leaves nothing in flight", state.busyTargets.isEmpty())
            // Reported once and dropped: nothing re-sends it.
            assertEquals(1, fake.onWithBrightness.size)

            fake.onWithBrightnessCommand = { _, _ -> HueResult.Ok(Unit) }
            controller.applyPreset(TARGET, LightingPreset.Bright)

            // The section recovered; the second command is the operator's next tap, not a replay.
            assertEquals(2, fake.onWithBrightness.size)
            assertEquals(null, controller.state.value.failure)
        }

    @Test fun openingAndLeavingTheSectionSendsNoPresetCommand() =
        runBlocking {
            val fake = RecordingHueLighting(groupedLightsResult = { HueResult.Ok(listOf(ON_GROUP)) })
            val controller = LightingController(fake)

            val visible = launch { controller.live() }
            withTimeout(TIMEOUT_MS) { fake.listeners.first { it > 0 } }
            visible.cancelAndJoin()

            // Connecting, reading and disconnecting are readiness only: a reconnect sends nothing.
            assertTrue(fake.onWithBrightness.isEmpty())
            assertTrue(fake.onOff.isEmpty())
            assertEquals(1, fake.connects)
            assertEquals(1, fake.disconnects)
        }

    @Test fun aBridgeFailureLeavesTheAppleTvSectionUsable() =
        runBlocking {
            val fake = RecordingHueLighting()
            fake.onWithBrightnessCommand = { _, _ -> HueResult.Failure(BRIDGE_FAILURE) }
            val presets = LightingController(fake)
            presets.applyPreset(TARGET, LightingPreset.Bright)
            assertEquals(BRIDGE_FAILURE, presets.state.value.failure)

            // The Apple TV section runs its own session with its own busy state; the bridge's failure
            // never reaches it, so its presses still dispatch.
            val press = CompletableDeferred<Unit>()
            var presses = 0
            val session = appleTvSession(this)
            session.run {
                presses++
                press.await()
            }
            yield()

            assertEquals(1, presses)
            assertTrue("the TV section is busy with its own press", session.busy.value)

            press.complete(Unit)
            withTimeout(TIMEOUT_MS) { session.busy.first { !it } }
            assertEquals(1, presses)
        }

    @Test fun aForgottenBridgeOrUnsetTargetHasNoPresetTargetSoNothingIsSent() {
        val bindings = SuperRemoteBindings(hueDeviceId = BRIDGE.id, hueTargetId = TARGET.id)

        // The bridge was forgotten: the bindings resolve to no lighting, and the section commands nothing.
        assertNull(presetTarget(bindings.resolve(listOf(OTHER_BRIDGE)).lighting, RecordingHueLighting()))
        // No room or zone was chosen yet, so there is no grouped light to command.
        assertNull(presetTarget(bindings.copy(hueTargetId = null).resolve(listOf(BRIDGE)).lighting, RecordingHueLighting()))
    }

    @Test fun aBridgeWithNoClientHasNoPresetTargetSoNothingIsSent() {
        assertNull(presetTarget(lighting, client = null))
    }

    @Test fun thePresetTargetIsExactlyTheConfiguredGroupedLight() {
        assertEquals(TARGET, presetTarget(lighting, RecordingHueLighting()))
    }

    @Test fun aDeletedTargetIsMissingOnceTheBridgeHasReportedWithoutIt() {
        val state = LightingState(loading = false, groupedLights = listOf(OTHER_GROUP))

        assertTrue(state.targetMissing(TARGET))
    }

    @Test fun aTargetTheBridgeStillReportsIsNotMissing() {
        val state = LightingState(loading = false, groupedLights = listOf(ON_GROUP))

        assertFalse(state.targetMissing(TARGET))
    }

    @Test fun aFailedReadDoesNotClaimTheTargetWasDeleted() {
        val state = LightingState(loading = false, failure = BRIDGE_FAILURE, groupedLights = emptyList())

        assertFalse(state.targetMissing(TARGET))
    }

    private fun appleTvSession(scope: CoroutineScope) =
        AppleTvSession(
            scope = scope,
            status = MutableStateFlow(CompanionClient.Result(true, "Connected to Apple TV.")),
            paired = MutableStateFlow(true),
            apps = MutableStateFlow(emptyList<AppleTvApp>()),
            connect = {},
            disconnect = {},
        )

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val BRIDGE_FAILURE = "Hue bridge unreachable. Check the Wi-Fi/LAN and that the bridge is on."

        val BRIDGE = SavedDevice("hue-1", DeviceKind.Hue, "Bridge", "192.168.1.40")
        val OTHER_BRIDGE = SavedDevice("hue-2", DeviceKind.Hue, "Upstairs", "192.168.1.41")
        val TARGET = HueCommandTarget.Group("grouped-1")
        val ON_GROUP = HueGroupedLight(id = "grouped-1", on = true, brightness = 100.0)
        val OFF_GROUP = HueGroupedLight(id = "grouped-1", on = false, brightness = null)
        val OTHER_GROUP = HueGroupedLight(id = "grouped-2", on = true, brightness = 55.0)
        val lighting = SuperRemoteLighting(BRIDGE, SuperRemoteLightTarget(TARGET.id, "Living room"))
    }
}

/** A [HueLighting] with no bridge: each call returns what the test decides and records what the section sent. */
private class RecordingHueLighting(
    var groupedLightsResult: suspend () -> HueResult<List<HueGroupedLight>> = { HueResult.Ok(emptyList()) },
) : HueLighting {
    /** Every combined on+brightness command, in order. */
    val onWithBrightness = mutableListOf<Pair<HueCommandTarget, Int>>()

    /** Every on/off command, in order. */
    val onOff = mutableListOf<Pair<HueCommandTarget, Boolean>>()

    /** Every brightness-only command, in order; a preset must never send one. */
    val brightness = mutableListOf<Pair<HueCommandTarget, Int>>()

    /** What [setOnWithBrightness] answers; defaults to success. */
    var onWithBrightnessCommand: suspend (HueCommandTarget, Int) -> HueResult<Unit> = { _, _ -> HueResult.Ok(Unit) }

    /** How many times the section opened the live subscription, and how many times it released it. */
    var connects = 0
    var disconnects = 0

    private val mutableState = MutableStateFlow<HueConnectionState>(HueConnectionState.Disconnected)
    private val mutableEvents = MutableSharedFlow<HueEvent>(extraBufferCapacity = EVENT_BUFFER)

    override val state: StateFlow<HueConnectionState> = mutableState

    override val events: SharedFlow<HueEvent> = mutableEvents

    /** How many live collectors are listening, so a test can wait until the section is subscribed. */
    val listeners: StateFlow<Int> get() = mutableEvents.subscriptionCount

    override suspend fun lights(): HueResult<List<HueLight>> = HueResult.Ok(emptyList())

    override suspend fun rooms(): HueResult<List<HueGroup>> = HueResult.Ok(emptyList())

    override suspend fun zones(): HueResult<List<HueGroup>> = HueResult.Ok(emptyList())

    override suspend fun groupedLights(): HueResult<List<HueGroupedLight>> = groupedLightsResult()

    override suspend fun setOn(
        target: HueCommandTarget,
        on: Boolean,
    ): HueResult<Unit> {
        onOff += target to on
        return HueResult.Ok(Unit)
    }

    override suspend fun setBrightness(
        target: HueCommandTarget,
        brightness: Int,
    ): HueResult<Unit> {
        this.brightness += target to brightness
        return HueResult.Ok(Unit)
    }

    override suspend fun setOnWithBrightness(
        target: HueCommandTarget,
        brightness: Int,
    ): HueResult<Unit> {
        onWithBrightness += target to brightness
        return onWithBrightnessCommand(target, brightness)
    }

    override fun connect() {
        connects += 1
    }

    override fun disconnect() {
        disconnects += 1
        mutableState.value = HueConnectionState.Disconnected
    }

    private companion object {
        const val EVENT_BUFFER = 16
    }
}
