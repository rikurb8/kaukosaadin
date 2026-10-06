package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.hue.HueConnectionState
import fi.goodconsulting.kaukosaadin.device.hue.HueCredentials
import fi.goodconsulting.kaukosaadin.device.hue.HueEvent
import fi.goodconsulting.kaukosaadin.device.hue.HueFavorites
import fi.goodconsulting.kaukosaadin.device.hue.HueGroupedLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import fi.goodconsulting.kaukosaadin.device.hue.HueResult
import fi.goodconsulting.kaukosaadin.device.hue.HueRoom
import fi.goodconsulting.kaukosaadin.device.hue.HueStorage
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
                lightingController(
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

    @Test fun loadRendersTheBridgesGroupedLights() =
        runBlocking {
            val controller =
                lightingController(
                    FakeHueLighting(
                        roomsResult = { HueResult.Ok(listOf(HALL_ROOM)) },
                        groupedLightsResult = { HueResult.Ok(listOf(HALL_GROUP)) },
                    ),
                )

            controller.load()

            assertEquals(listOf(HALL_GROUP), controller.state.value.groupedLights)
        }

    @Test fun aFailedLoadSurfacesItsMessage() =
        runBlocking {
            val controller = lightingController(FakeHueLighting(lightsResult = { HueResult.Failure(LIGHTS_FAILURE) }))

            controller.load()

            assertEquals(LIGHTS_FAILURE, controller.state.value.failure)
            assertFalse(controller.state.value.loading)
        }

    @Test fun aFailedReadKeepsWhatTheOtherReadReturned() =
        runBlocking {
            val controller =
                lightingController(
                    FakeHueLighting(
                        lightsResult = { HueResult.Ok(listOf(KITCHEN)) },
                        roomsResult = { HueResult.Failure(ROOMS_FAILURE) },
                    ),
                )

            controller.load()

            assertEquals(listOf(KITCHEN), controller.state.value.lights)
            assertEquals(ROOMS_FAILURE, controller.state.value.failure)
        }

    @Test fun aFailedGroupedLightReadKeepsTheLightsAndSurfacesItsMessage() =
        runBlocking {
            val controller =
                lightingController(
                    FakeHueLighting(
                        lightsResult = { HueResult.Ok(listOf(KITCHEN)) },
                        groupedLightsResult = { HueResult.Failure(GROUPS_FAILURE) },
                    ),
                )

            controller.load()

            assertEquals(listOf(KITCHEN), controller.state.value.lights)
            assertEquals(GROUPS_FAILURE, controller.state.value.failure)
        }

    @Test fun toggleSendsOneCommandAndDisablesTheLightWhileInFlight() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN)) })
            fake.onCommand = { _, _ ->
                gate.await()
                HueResult.Ok(Unit)
            }
            val controller = lightingController(fake)
            controller.load()

            val toggle = launch { controller.toggle(KITCHEN) }
            withTimeout(TIMEOUT_MS) { controller.state.first { KITCHEN.id in it.busyTargets } }

            assertEquals(listOf(HueTarget.Light(KITCHEN.id) to true), fake.commands)

            gate.complete(Unit)
            toggle.join()
            val state = controller.state.value
            assertTrue(state.busyTargets.isEmpty())
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
            val controller = lightingController(fake)
            controller.load()

            val first = launch { controller.toggle(KITCHEN) }
            withTimeout(TIMEOUT_MS) { controller.state.first { KITCHEN.id in it.busyTargets } }

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
            val controller = lightingController(fake)
            controller.load()

            controller.toggle(KITCHEN)

            val state = controller.state.value
            assertEquals(TOGGLE_FAILURE, state.failure)
            assertTrue(state.busyTargets.isEmpty())
            assertFalse(state.lights.single().on)
        }

    @Test fun aToggledRoomCommandsItsGroupedLight() =
        runBlocking {
            val fake =
                FakeHueLighting(
                    roomsResult = { HueResult.Ok(listOf(KITCHEN_ROOM)) },
                    groupedLightsResult = { HueResult.Ok(listOf(KITCHEN_GROUP)) },
                )
            val controller = lightingController(fake)
            controller.load()

            controller.toggle(KITCHEN_ROOM)

            val state = controller.state.value
            assertEquals(listOf(HueTarget.Group(KITCHEN_GROUP.id) to true), fake.commands)
            assertTrue(state.groupedLights.single().on)
        }

    @Test fun aRoomWithNoGroupedLightIsNotControllable() =
        runBlocking {
            val fake = FakeHueLighting()
            val controller = lightingController(fake)
            controller.load()

            controller.toggle(NO_GROUP_ROOM)
            controller.toggle(ROOM_WITH_MISSING_GROUP)

            assertTrue(fake.commands.isEmpty())
            assertEquals(null, controller.state.value.groupedLightFor(NO_GROUP_ROOM))
            assertEquals(null, controller.state.value.groupedLightFor(ROOM_WITH_MISSING_GROUP))
        }

    @Test fun draggingTheBrightnessSliderSendsNothing() =
        runBlocking {
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN_LAMP)) })
            val controller = lightingController(fake)
            controller.load()
            val target = HueTarget.Light(KITCHEN_LAMP.id)

            controller.dragBrightness(target, 35)
            controller.dragBrightness(target, 80)

            assertTrue(fake.brightnessCommands.isEmpty())
            assertTrue(fake.commands.isEmpty())
            assertEquals(80, controller.state.value.brightnessDrafts[KITCHEN_LAMP.id])
        }

    @Test fun releasingTheBrightnessSliderSendsOneCommandAndNoOnOff() =
        runBlocking {
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN_LAMP)) })
            val controller = lightingController(fake)
            controller.load()
            val target = HueTarget.Light(KITCHEN_LAMP.id)
            controller.dragBrightness(target, 35)
            controller.dragBrightness(target, 80)

            controller.releaseBrightness(target)

            val state = controller.state.value
            assertEquals(listOf(target to 80), fake.brightnessCommands)
            assertEquals(emptyList<Pair<HueTarget, Boolean>>(), fake.commands)
            assertTrue(state.brightnessDrafts.isEmpty())
            assertEquals(80.0, state.lights.single().brightness)
        }

    @Test fun aRoomsBrightnessTargetsItsGroupedLight() =
        runBlocking {
            val fake =
                FakeHueLighting(
                    roomsResult = { HueResult.Ok(listOf(HALL_ROOM)) },
                    groupedLightsResult = { HueResult.Ok(listOf(HALL_GROUP)) },
                )
            val controller = lightingController(fake)
            controller.load()
            val target = HueTarget.Group(HALL_GROUP.id)

            assertTrue(controller.state.value.brightnessEnabled(target))
            controller.dragBrightness(target, 65)
            controller.releaseBrightness(target)

            val state = controller.state.value
            assertEquals(listOf(target to 65), fake.brightnessCommands)
            assertTrue(fake.commands.isEmpty())
            assertEquals(65.0, state.groupedLights.single().brightness)
        }

    @Test fun brightnessIsNotSentWhileTheLightIsOff() =
        runBlocking {
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN)) })
            val controller = lightingController(fake)
            controller.load()
            val target = HueTarget.Light(KITCHEN.id)

            assertFalse(controller.state.value.brightnessEnabled(target))

            controller.dragBrightness(target, 30)
            controller.releaseBrightness(target)

            val state = controller.state.value
            assertTrue(fake.brightnessCommands.isEmpty())
            assertTrue(state.brightnessDrafts.isEmpty())
        }

    @Test fun brightnessIsNotSentWhileTheGroupIsOff() =
        runBlocking {
            val fake =
                FakeHueLighting(
                    roomsResult = { HueResult.Ok(listOf(KITCHEN_ROOM)) },
                    groupedLightsResult = { HueResult.Ok(listOf(KITCHEN_GROUP)) },
                )
            val controller = lightingController(fake)
            controller.load()
            val target = HueTarget.Group(KITCHEN_GROUP.id)

            assertFalse(controller.state.value.brightnessEnabled(target))

            controller.dragBrightness(target, 30)
            controller.releaseBrightness(target)

            val state = controller.state.value
            assertTrue(fake.brightnessCommands.isEmpty())
            assertTrue(state.brightnessDrafts.isEmpty())
        }

    @Test fun aSecondBrightnessReleaseWhileOneIsInFlightIsDropped() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN_LAMP)) })
            fake.brightnessCommand = { _, _ ->
                gate.await()
                HueResult.Ok(Unit)
            }
            val controller = lightingController(fake)
            controller.load()
            val target = HueTarget.Light(KITCHEN_LAMP.id)
            controller.dragBrightness(target, 55)

            val first = launch { controller.releaseBrightness(target) }
            withTimeout(TIMEOUT_MS) { controller.state.first { target.id in it.busyTargets } }

            val second = launch { controller.releaseBrightness(target) }
            withTimeout(TIMEOUT_MS) { while (second.isActive) yield() }

            assertEquals(1, fake.brightnessCommands.size)
            gate.complete(Unit)
            first.join()

            val state = controller.state.value
            assertTrue(state.busyTargets.isEmpty())
        }

    @Test fun aFavoriteLightSurvivesARestart() =
        runBlocking {
            val storage = FakeStorage()
            val beforeRestart =
                lightingController(
                    FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN_LAMP)) }),
                    storage,
                )
            beforeRestart.load()

            beforeRestart.toggleFavorite(KITCHEN_LAMP)

            // A fresh controller over the same stored bytes, as the screen builds after a restart.
            val afterRestart =
                lightingController(
                    FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN_LAMP)) }),
                    storage,
                )
            afterRestart.load()

            assertEquals(setOf(KITCHEN_LAMP.id), afterRestart.state.value.favoriteLights)
        }

    @Test fun aFailedBrightnessReleaseSurfacesItsMessage() =
        runBlocking {
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN_LAMP)) })
            fake.brightnessCommand = { _, _ -> HueResult.Failure(BRIGHTNESS_FAILURE) }
            val controller = lightingController(fake)
            controller.load()
            val target = HueTarget.Light(KITCHEN_LAMP.id)
            controller.dragBrightness(target, 55)

            controller.releaseBrightness(target)

            val state = controller.state.value
            assertEquals(BRIGHTNESS_FAILURE, state.failure)
            assertTrue(state.brightnessDrafts.isEmpty())
            assertTrue(state.busyTargets.isEmpty())
            assertEquals(40.0, state.lights.single().brightness)
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val LIGHTS_FAILURE = "The bridge rejected the app key. Pair the bridge again."
        const val ROOMS_FAILURE = "Hue bridge unreachable. Check the Wi-Fi/LAN and that the bridge is on."
        const val GROUPS_FAILURE = "The bridge refused the request (Hue error 901)."
        const val TOGGLE_FAILURE = "The bridge no longer has that light, room or group."
        const val BRIGHTNESS_FAILURE = "The bridge refused the request (503)."

        val KITCHEN = HueLight(id = "light-1", name = "Kitchen", on = false, brightness = null)
        val KITCHEN_LAMP = HueLight(id = "light-2", name = "Kitchen lamp", on = true, brightness = 40.0)
        val KITCHEN_ROOM = HueRoom(id = "room-1", name = "Kitchen", groupedLightId = "grouped-1")
        val KITCHEN_GROUP = HueGroupedLight(id = "grouped-1", on = false, brightness = null)
        val HALL_ROOM = HueRoom(id = "room-2", name = "Hall", groupedLightId = "grouped-2")
        val HALL_GROUP = HueGroupedLight(id = "grouped-2", on = true, brightness = 55.0)
        val NO_GROUP_ROOM = HueRoom(id = "room-3", name = "Garage", groupedLightId = null)
        val ROOM_WITH_MISSING_GROUP = HueRoom(id = "room-4", name = "Attic", groupedLightId = "grouped-missing")
    }
}

/** A controller over [lighting] whose bridge keeps [storage], so a favorite can be persisted and re-read. */
private fun lightingController(
    lighting: HueLighting,
    storage: HueStorage = FakeStorage(),
): LightingController = LightingController(lighting, HueFavorites(storage))

/** The in-memory stand-in for one bridge's stored settings file. */
private class FakeStorage : HueStorage {
    val values = mutableMapOf<String, String>()

    override fun get(name: String): String? = values[name]

    override fun put(
        name: String,
        value: String?,
    ): Boolean {
        if (value == null) values.remove(name) else values[name] = value
        return true
    }

    override fun clear(): Boolean {
        values.clear()
        return true
    }
}

/** A [HueLighting] with no bridge: each call returns whatever the test's lambdas decide. */
private class FakeHueLighting(
    private val lightsResult: suspend () -> HueResult<List<HueLight>> = { HueResult.Ok(emptyList()) },
    private val roomsResult: suspend () -> HueResult<List<HueRoom>> = { HueResult.Ok(emptyList()) },
    private val groupedLightsResult: suspend () -> HueResult<List<HueGroupedLight>> = { HueResult.Ok(emptyList()) },
) : HueLighting {
    /** Every on/off command sent, in order. */
    val commands = mutableListOf<Pair<HueTarget, Boolean>>()

    /** Every brightness command sent, in order. */
    val brightnessCommands = mutableListOf<Pair<HueTarget, Int>>()

    /** What [setOn] answers; defaults to success. */
    var onCommand: suspend (HueTarget, Boolean) -> HueResult<Unit> = { _, _ -> HueResult.Ok(Unit) }

    /** What [setBrightness] answers; defaults to success. */
    var brightnessCommand: suspend (HueTarget, Int) -> HueResult<Unit> = { _, _ -> HueResult.Ok(Unit) }

    override val state: StateFlow<HueConnectionState> = MutableStateFlow(HueConnectionState.Disconnected)

    override val events: SharedFlow<HueEvent> = MutableSharedFlow()

    override suspend fun lights(): HueResult<List<HueLight>> = lightsResult()

    override suspend fun rooms(): HueResult<List<HueRoom>> = roomsResult()

    override suspend fun groupedLights(): HueResult<List<HueGroupedLight>> = groupedLightsResult()

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
    ): HueResult<Unit> {
        brightnessCommands += target to brightness
        return brightnessCommand(target, brightness)
    }

    override fun connect() = Unit

    override fun disconnect() = Unit
}
