package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.hue.HueConnectionState
import fi.goodconsulting.kaukosaadin.device.hue.HueEvent
import fi.goodconsulting.kaukosaadin.device.hue.HueGroupedLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import fi.goodconsulting.kaukosaadin.device.hue.HueResult
import fi.goodconsulting.kaukosaadin.device.hue.HueRoom
import fi.goodconsulting.kaukosaadin.device.hue.HueTarget
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Test

class LightingControllerTest {
    @Test fun loadRendersTheBridgesLightsAndRooms() =
        runBlocking {
            val controller =
                LightingController(
                    FakeHueLighting(
                        lightsResult = { HueResult.Ok(listOf(KITCHEN)) },
                        roomsResult = { HueResult.Ok(listOf(KITCHEN_ROOM)) },
                    ),
                )

            controller.load()

            val state = controller.state.value
            assertEquals(listOf(KITCHEN), state.lights)
            assertEquals(listOf(KITCHEN_ROOM), state.rooms)
            assertFalse(state.loading)
            assertEquals(null, state.failure)
        }

    @Test fun aFailedLoadSurfacesItsMessage() =
        runBlocking {
            val controller = LightingController(FakeHueLighting(lightsResult = { HueResult.Failure(LIGHTS_FAILURE) }))

            controller.load()

            assertEquals(LIGHTS_FAILURE, controller.state.value.failure)
            assertFalse(controller.state.value.loading)
        }

    @Test fun aFailedReadKeepsWhatTheOtherReadReturned() =
        runBlocking {
            val controller =
                LightingController(
                    FakeHueLighting(
                        lightsResult = { HueResult.Ok(listOf(KITCHEN)) },
                        roomsResult = { HueResult.Failure(ROOMS_FAILURE) },
                    ),
                )

            controller.load()

            assertEquals(listOf(KITCHEN), controller.state.value.lights)
            assertEquals(ROOMS_FAILURE, controller.state.value.failure)
        }

    @Test fun toggleSendsOneCommandAndDisablesTheLightWhileInFlight() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN)) })
            fake.onCommand = { _, _ ->
                gate.await()
                HueResult.Ok(Unit)
            }
            val controller = LightingController(fake)
            controller.load()

            val toggle = launch { controller.toggle(KITCHEN) }
            withTimeout(TIMEOUT_MS) { controller.state.first { KITCHEN.id in it.busyLights } }

            assertEquals(listOf(HueTarget.Light(KITCHEN.id) to true), fake.commands)

            gate.complete(Unit)
            toggle.join()
            val state = controller.state.value
            assertTrue(state.busyLights.isEmpty())
            assertTrue(state.lights.single().on)
        }

    @Test fun aToggleWhileTheLightIsBusyIsIgnored() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN)) })
            fake.onCommand = { _, _ ->
                gate.await()
                HueResult.Ok(Unit)
            }
            val controller = LightingController(fake)
            controller.load()

            val first = launch { controller.toggle(KITCHEN) }
            withTimeout(TIMEOUT_MS) { controller.state.first { KITCHEN.id in it.busyLights } }

            val second = launch { controller.toggle(KITCHEN) }
            withTimeout(TIMEOUT_MS) { while (second.isActive) yield() }

            assertEquals(1, fake.commands.size)
            gate.complete(Unit)
            first.join()
        }

    @Test fun aFailedToggleSurfacesItsMessageAndReenablesTheLight() =
        runBlocking {
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN)) })
            fake.onCommand = { _, _ -> HueResult.Failure(TOGGLE_FAILURE) }
            val controller = LightingController(fake)
            controller.load()

            controller.toggle(KITCHEN)

            val state = controller.state.value
            assertEquals(TOGGLE_FAILURE, state.failure)
            assertTrue(state.busyLights.isEmpty())
            assertFalse(state.lights.single().on)
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val LIGHTS_FAILURE = "The bridge rejected the app key. Pair the bridge again."
        const val ROOMS_FAILURE = "Hue bridge unreachable. Check the Wi-Fi/LAN and that the bridge is on."
        const val TOGGLE_FAILURE = "The bridge no longer has that light, room or group."

        val KITCHEN = HueLight(id = "light-1", name = "Kitchen", on = false, brightness = null)
        val KITCHEN_ROOM = HueRoom(id = "room-1", name = "Kitchen", groupedLightId = "grouped-1")
    }
}

/** A [HueLighting] with no bridge: each call returns whatever the test's lambdas decide. */
private class FakeHueLighting(
    private val lightsResult: suspend () -> HueResult<List<HueLight>> = { HueResult.Ok(emptyList()) },
    private val roomsResult: suspend () -> HueResult<List<HueRoom>> = { HueResult.Ok(emptyList()) },
) : HueLighting {
    /** Every on/off command sent, in order. */
    val commands = mutableListOf<Pair<HueTarget, Boolean>>()

    /** What [setOn] answers; defaults to success. */
    var onCommand: suspend (HueTarget, Boolean) -> HueResult<Unit> = { _, _ -> HueResult.Ok(Unit) }

    override val state: StateFlow<HueConnectionState> = MutableStateFlow(HueConnectionState.Disconnected)

    override val events: SharedFlow<HueEvent> = MutableSharedFlow()

    override suspend fun lights(): HueResult<List<HueLight>> = lightsResult()

    override suspend fun rooms(): HueResult<List<HueRoom>> = roomsResult()

    override suspend fun groupedLights(): HueResult<List<HueGroupedLight>> = HueResult.Ok(emptyList())

    override suspend fun setOn(
        target: HueTarget,
        on: Boolean,
    ): HueResult<Unit> {
        commands += target to on
        return onCommand(target, on)
    }

    override suspend fun setBrightness(
        target: HueTarget,
        brightness: Int,
    ): HueResult<Unit> = HueResult.Ok(Unit)

    override fun connect() = Unit

    override fun disconnect() = Unit
}
