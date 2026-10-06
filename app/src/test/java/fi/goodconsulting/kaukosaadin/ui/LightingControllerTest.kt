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
            // Reported once and dropped: nothing re-sends it.
            assertEquals(1, fake.commands.size)
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

    @Test fun favoriteLightsAreListedFirstWithoutHidingOrRepeatingAnyLight() =
        runBlocking {
            val storage = FakeStorage()
            val lights = listOf(DESK_LAMP, KITCHEN_LAMP, HALL_LAMP)
            val beforeRestart = lightingController(FakeHueLighting(lightsResult = { HueResult.Ok(lights) }), storage)
            beforeRestart.load()

            beforeRestart.toggleFavorite(HALL_LAMP)

            val afterRestart = lightingController(FakeHueLighting(lightsResult = { HueResult.Ok(lights) }), storage)
            afterRestart.load()

            val listed = afterRestart.state.value.orderedLights
            assertEquals(listOf(HALL_LAMP, DESK_LAMP, KITCHEN_LAMP), listed)
            // The one list split in two: every light is still here, and none of them twice.
            assertEquals(lights.size, listed.size)
            assertEquals(lights.toSet(), listed.toSet())
        }

    @Test fun favoriteRoomsAreListedFirstWithoutHidingOrRepeatingAnyRoom() =
        runBlocking {
            val storage = FakeStorage()
            val rooms = listOf(KITCHEN_ROOM, HALL_ROOM, NO_GROUP_ROOM)
            val beforeRestart = lightingController(FakeHueLighting(roomsResult = { HueResult.Ok(rooms) }), storage)
            beforeRestart.load()

            beforeRestart.toggleFavorite(HALL_ROOM)

            val afterRestart = lightingController(FakeHueLighting(roomsResult = { HueResult.Ok(rooms) }), storage)
            afterRestart.load()

            val listed = afterRestart.state.value.orderedRooms
            assertEquals(listOf(HALL_ROOM, KITCHEN_ROOM, NO_GROUP_ROOM), listed)
            assertEquals(rooms.size, listed.size)
            assertEquals(rooms.toSet(), listed.toSet())
        }

    @Test fun unfavoritingRestoresTheBridgesOwnOrder() =
        runBlocking {
            val lights = listOf(DESK_LAMP, KITCHEN_LAMP, HALL_LAMP)
            val controller = lightingController(FakeHueLighting(lightsResult = { HueResult.Ok(lights) }))
            controller.load()
            controller.toggleFavorite(HALL_LAMP)
            assertEquals(listOf(HALL_LAMP, DESK_LAMP, KITCHEN_LAMP), controller.state.value.orderedLights)

            controller.toggleFavorite(HALL_LAMP)

            val state = controller.state.value
            assertEquals(lights, state.orderedLights)
            assertTrue(state.favoriteLights.isEmpty())
        }

    @Test fun aFavoritedLightTheBridgeNoLongerReportsIsListedNowhere() =
        runBlocking {
            val storage = FakeStorage()
            val beforeRestart =
                lightingController(
                    FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN_LAMP, HALL_LAMP)) }),
                    storage,
                )
            beforeRestart.load()
            beforeRestart.toggleFavorite(KITCHEN_LAMP)

            // The bridge no longer reports the favorited light; the lights it does report still list.
            val afterRestart =
                lightingController(
                    FakeHueLighting(lightsResult = { HueResult.Ok(listOf(HALL_LAMP)) }),
                    storage,
                )
            afterRestart.load()

            val state = afterRestart.state.value
            assertEquals(listOf(HALL_LAMP), state.orderedLights)
            assertEquals(setOf(KITCHEN_LAMP.id), state.favoriteLights)
            assertEquals(null, state.failure)
        }

    @Test fun forgettingTheBridgeClearsItsFavorites() =
        runBlocking {
            val storage = FakeStorage()
            val controller = lightingController(FakeHueLighting(), storage)
            controller.toggleFavorite(KITCHEN_LAMP)
            controller.toggleFavorite(KITCHEN_ROOM)
            assertTrue(storage.values.isNotEmpty())

            // The forget path the shell calls: HueClient.forget → HueCredentials.forget → the one stored file.
            assertTrue(HueCredentials(storage, key = { error("A unit test never seals a pairing.") }).forget())

            val afterForget =
                lightingController(FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN_LAMP)) }), storage)
            afterForget.load()

            val state = afterForget.state.value
            assertTrue(state.favoriteLights.isEmpty())
            assertTrue(state.favoriteRooms.isEmpty())
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

    @Test fun anEventUpdatesALightsOnOffAndBrightness() =
        runBlocking {
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN, HALL_LAMP)) })
            val controller = lightingController(fake)
            val visible = launch { controller.live() }
            withTimeout(TIMEOUT_MS) { fake.listeners.first { it > 0 } }

            // As a physical switch or another app would report it.
            fake.emit(HueEvent(action = "update", resourceId = KITCHEN.id, resourceType = "light", on = true, brightness = 61.0))

            val state = withTimeout(TIMEOUT_MS) { controller.state.first { it.lights.first { light -> light.id == KITCHEN.id }.on } }
            val kitchen = state.lights.first { it.id == KITCHEN.id }
            assertTrue(kitchen.on)
            assertEquals(61.0, kitchen.brightness!!, 0.0)
            // The light the event did not name keeps what the bridge last reported.
            assertEquals(HALL_LAMP, state.lights.first { it.id == HALL_LAMP.id })
            visible.cancelAndJoin()
        }

    @Test fun anEventUpdatesAGroupedLight() =
        runBlocking {
            val fake =
                FakeHueLighting(
                    roomsResult = { HueResult.Ok(listOf(HALL_ROOM)) },
                    groupedLightsResult = { HueResult.Ok(listOf(HALL_GROUP)) },
                )
            val controller = lightingController(fake)
            val visible = launch { controller.live() }
            withTimeout(TIMEOUT_MS) { fake.listeners.first { it > 0 } }

            fake.emit(
                HueEvent(action = "update", resourceId = HALL_GROUP.id, resourceType = "grouped_light", on = false, brightness = 20.0),
            )

            val group = withTimeout(TIMEOUT_MS) { controller.state.first { !it.groupedLights.single().on } }.groupedLights.single()
            assertFalse(group.on)
            assertEquals(20.0, group.brightness!!, 0.0)
            visible.cancelAndJoin()
        }

    @Test fun anEventForAnUnknownResourceChangesNothing() =
        runBlocking {
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN)) })
            val controller = lightingController(fake)
            val visible = launch { controller.live() }
            withTimeout(TIMEOUT_MS) { fake.listeners.first { it > 0 } }

            // Two resources the screen does not track, then one it does: the unknown ones are processed
            // first, and the light the screen tracks is the only thing they could have touched.
            fake.emit(HueEvent(action = "update", resourceId = "light-gone", resourceType = "light", on = true, brightness = 50.0))
            fake.emit(HueEvent(action = "update", resourceId = "scene-1", resourceType = "scene", on = true, brightness = 50.0))
            fake.emit(HueEvent(action = "update", resourceId = KITCHEN.id, resourceType = "light", on = true, brightness = null))

            val state = withTimeout(TIMEOUT_MS) { controller.state.first { it.lights.single().on } }
            assertEquals(listOf(KITCHEN.copy(on = true)), state.lights)
            visible.cancelAndJoin()
        }

    @Test fun anEventNeverSendsACommand() =
        runBlocking {
            val fake =
                FakeHueLighting(
                    lightsResult = { HueResult.Ok(listOf(KITCHEN)) },
                    roomsResult = { HueResult.Ok(listOf(KITCHEN_ROOM)) },
                    groupedLightsResult = { HueResult.Ok(listOf(KITCHEN_GROUP)) },
                )
            val controller = lightingController(fake)
            val visible = launch { controller.live() }
            withTimeout(TIMEOUT_MS) { fake.listeners.first { it > 0 } }

            fake.emit(HueEvent(action = "update", resourceId = KITCHEN.id, resourceType = "light", on = true, brightness = 40.0))
            fake.emit(
                HueEvent(action = "update", resourceId = KITCHEN_GROUP.id, resourceType = "grouped_light", on = true, brightness = 30.0),
            )
            withTimeout(TIMEOUT_MS) { controller.state.first { it.lights.single().on && it.groupedLights.single().on } }

            assertTrue(fake.commands.isEmpty())
            assertTrue(fake.brightnessCommands.isEmpty())
            visible.cancelAndJoin()
        }

    @Test fun openingTheScreenConnectsAndLeavingItDisconnects() =
        runBlocking {
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN)) })
            val controller = lightingController(fake)

            val visible = launch { controller.live() }
            withTimeout(TIMEOUT_MS) { fake.listeners.first { it > 0 } }

            assertEquals(1, fake.connects)
            assertEquals(0, fake.disconnects)

            // Leaving the screen, or the app going to the background, cancels the live lifetime.
            visible.cancelAndJoin()

            assertEquals(1, fake.disconnects)
        }

    @Test fun returningToTheScreenRefreshesWhatChangedWhileItWasGone() =
        runBlocking {
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN)) })
            val controller = lightingController(fake)

            val visible = launch { controller.live() }
            withTimeout(TIMEOUT_MS) { controller.state.first { it.lights.isNotEmpty() } }
            assertEquals(listOf(KITCHEN), controller.state.value.lights)
            visible.cancelAndJoin()

            // The bridge moved on while the screen was backgrounded; the next entry reads it again.
            fake.lightsResult = { HueResult.Ok(listOf(KITCHEN_LAMP)) }

            val returned = launch { controller.live() }
            withTimeout(TIMEOUT_MS) { controller.state.first { it.lights == listOf(KITCHEN_LAMP) } }
            returned.cancelAndJoin()

            assertEquals(2, fake.connects)
        }

    @Test fun aFailedLiveConnectionSurfacesItsMessage() =
        runBlocking {
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN)) })
            fake.connectionFailure = CONNECTION_FAILURE
            val controller = lightingController(fake)

            val visible = launch { controller.live() }
            withTimeout(TIMEOUT_MS) { fake.listeners.first { it > 0 } }

            assertEquals(CONNECTION_FAILURE, failureMessage(controller.connection.value, controller.state.value))
            visible.cancelAndJoin()
        }

    @Test fun theFailureBannerPrefersTheLiveConnectionsFailure() {
        val controller = lightingController(FakeHueLighting())
        val readOrCommandFailure = controller.state.value.copy(failure = TOGGLE_FAILURE)

        assertEquals(CONNECTION_FAILURE, failureMessage(HueConnectionState.Failed(CONNECTION_FAILURE), readOrCommandFailure))
        assertEquals(TOGGLE_FAILURE, failureMessage(HueConnectionState.Connected, readOrCommandFailure))
        assertEquals(TOGGLE_FAILURE, failureMessage(HueConnectionState.Connecting, readOrCommandFailure))
        assertEquals(null, failureMessage(HueConnectionState.Disconnected, controller.state.value))
    }

    @Test fun aToggleUsesTheLightsLiveStateNotTheRenderedSnapshot() =
        runBlocking {
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN)) })
            val controller = lightingController(fake)
            val visible = launch { controller.live() }
            withTimeout(TIMEOUT_MS) { fake.listeners.first { it > 0 } }

            // The screen rendered this light while it was off.
            val lights = controller.state.value.lights
            val rendered = lights.single()
            assertFalse(rendered.on)

            // A physical switch turns it on before the operator's tap reaches the controller.
            fake.emit(HueEvent(action = "update", resourceId = KITCHEN.id, resourceType = "light", on = true, brightness = null))
            withTimeout(TIMEOUT_MS) { controller.state.first { it.lights.single().on } }

            controller.toggle(rendered)

            // The command follows the light's live state (on → off), not the stale rendered snapshot (off → on).
            assertEquals(listOf(HueTarget.Light(KITCHEN.id) to false), fake.commands)
            visible.cancelAndJoin()
        }

    @Test fun aToggleAlreadyInFlightIsDroppedEvenWhenAnEventChangedTheLight() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fake = FakeHueLighting(lightsResult = { HueResult.Ok(listOf(KITCHEN)) })
            fake.onCommand = { _, _ ->
                gate.await()
                HueResult.Ok(Unit)
            }
            val controller = lightingController(fake)
            val visible = launch { controller.live() }
            withTimeout(TIMEOUT_MS) { fake.listeners.first { it > 0 } }

            val first = launch { controller.toggle(KITCHEN) }
            withTimeout(TIMEOUT_MS) { controller.state.first { KITCHEN.id in it.busyTargets } }

            fake.emit(HueEvent(action = "update", resourceId = KITCHEN.id, resourceType = "light", on = false, brightness = null))

            val second = launch { controller.toggle(KITCHEN) }
            withTimeout(TIMEOUT_MS) { while (second.isActive) yield() }

            assertEquals(listOf(HueTarget.Light(KITCHEN.id) to true), fake.commands)

            gate.complete(Unit)
            first.join()
            visible.cancelAndJoin()
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val LIGHTS_FAILURE = "The bridge rejected the app key. Pair the bridge again."
        const val ROOMS_FAILURE = "Hue bridge unreachable. Check the Wi-Fi/LAN and that the bridge is on."
        const val GROUPS_FAILURE = "The bridge refused the request (Hue error 901)."
        const val TOGGLE_FAILURE = "The bridge no longer has that light, room or group."
        const val BRIGHTNESS_FAILURE = "The bridge refused the request (503)."
        const val CONNECTION_FAILURE = "The bridge closed the live subscription."

        val KITCHEN = HueLight(id = "light-1", name = "Kitchen", on = false, brightness = null)
        val KITCHEN_LAMP = HueLight(id = "light-2", name = "Kitchen lamp", on = true, brightness = 40.0)
        val HALL_LAMP = HueLight(id = "light-3", name = "Hall lamp", on = true, brightness = 70.0)
        val DESK_LAMP = HueLight(id = "light-4", name = "Desk lamp", on = false, brightness = null)
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
    var lightsResult: suspend () -> HueResult<List<HueLight>> = { HueResult.Ok(emptyList()) },
    var roomsResult: suspend () -> HueResult<List<HueRoom>> = { HueResult.Ok(emptyList()) },
    var groupedLightsResult: suspend () -> HueResult<List<HueGroupedLight>> = { HueResult.Ok(emptyList()) },
) : HueLighting {
    /** Every on/off command sent, in order. */
    val commands = mutableListOf<Pair<HueTarget, Boolean>>()

    /** Every brightness command sent, in order. */
    val brightnessCommands = mutableListOf<Pair<HueTarget, Int>>()

    /** What [setOn] answers; defaults to success. */
    var onCommand: suspend (HueTarget, Boolean) -> HueResult<Unit> = { _, _ -> HueResult.Ok(Unit) }

    /** What [setBrightness] answers; defaults to success. */
    var brightnessCommand: suspend (HueTarget, Int) -> HueResult<Unit> = { _, _ -> HueResult.Ok(Unit) }

    /** How many times the screen opened the live subscription, and how many times it released it. */
    var connects = 0
    var disconnects = 0

    /** When set, [connect] reports this as the failed live connection, like a bridge that refuses the stream. */
    var connectionFailure: String? = null

    private val mutableState = MutableStateFlow<HueConnectionState>(HueConnectionState.Disconnected)
    private val mutableEvents = MutableSharedFlow<HueEvent>(extraBufferCapacity = EVENT_BUFFER)

    override val state: StateFlow<HueConnectionState> = mutableState

    override val events: SharedFlow<HueEvent> = mutableEvents

    /** How many live collectors are listening, so a test can wait until the screen is subscribed. */
    val listeners: StateFlow<Int> get() = mutableEvents.subscriptionCount

    /** Delivers one bridge-reported change to whoever is collecting. */
    suspend fun emit(event: HueEvent) = mutableEvents.emit(event)

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

    override fun connect() {
        connects += 1
        connectionFailure?.let { mutableState.value = HueConnectionState.Failed(it) }
    }

    override fun disconnect() {
        disconnects += 1
        mutableState.value = HueConnectionState.Disconnected
    }

    private companion object {
        const val EVENT_BUFFER = 16
    }
}
