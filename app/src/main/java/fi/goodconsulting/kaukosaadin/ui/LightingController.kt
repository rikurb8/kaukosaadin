package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.hue.HueConnectionState
import fi.goodconsulting.kaukosaadin.device.hue.HueLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import fi.goodconsulting.kaukosaadin.device.hue.HueResult
import fi.goodconsulting.kaukosaadin.device.hue.HueRoom
import fi.goodconsulting.kaukosaadin.device.hue.HueTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the lighting screen renders for the selected bridge: its rooms and lights, and any failure. */
internal data class LightingState(
    val rooms: List<HueRoom> = emptyList(),
    val lights: List<HueLight> = emptyList(),
    val loading: Boolean = true,
    /** The most recent failure to show, or null. A failed load or command surfaces it; a success clears it. */
    val failure: String? = null,
    /** Light ids with an on/off command in flight; their switches are disabled meanwhile. */
    val busyLights: Set<String> = emptySet(),
)

/**
 * The lighting screen's state and commands, kept outside Compose so the screen only renders what
 * this derives. [load] fetches the bridge's rooms and lights when the screen opens; [toggle] sends
 * one on/off command per light and ignores a tap while that light's command is still in flight.
 * A failed read or command becomes [LightingState.failure]; nothing throws. Ticket #10's
 * groups/brightness, #11's favorites and #12's live stream extend this, not the screen.
 */
internal class LightingController(
    private val lighting: HueLighting,
) {
    private val mutableState = MutableStateFlow(LightingState())

    /** Everything the lighting screen shows. */
    val state: StateFlow<LightingState> = mutableState.asStateFlow()

    /** The bridge's live-subscription state; the screen renders its failure (ticket #12 drives it). */
    val connection: StateFlow<HueConnectionState> = lighting.state

    /** Fetches rooms and lights once, when the screen opens; a failed read keeps what the other returned. */
    suspend fun load() {
        mutableState.update { it.copy(loading = true, failure = null) }
        val lights = lighting.lights()
        val rooms = lighting.rooms()
        mutableState.update {
            it.copy(
                loading = false,
                lights = (lights as? HueResult.Ok)?.value ?: it.lights,
                rooms = (rooms as? HueResult.Ok)?.value ?: it.rooms,
                failure = lights.failure() ?: rooms.failure(),
            )
        }
    }

    /** Sends one on/off command for [light]; a tap while that light's command is in flight is ignored. */
    suspend fun toggle(light: HueLight) {
        if (!begin(light.id)) return
        val on = !light.on
        try {
            when (val result = lighting.setOn(HueTarget.Light(light.id), on)) {
                is HueResult.Ok ->
                    mutableState.update {
                        it.copy(
                            lights = it.lights.map { entry -> if (entry.id == light.id) entry.copy(on = on) else entry },
                            failure = null,
                        )
                    }
                is HueResult.Failure -> mutableState.update { it.copy(failure = result.message) }
            }
        } finally {
            mutableState.update { it.copy(busyLights = it.busyLights - light.id) }
        }
    }

    /** Marks [id] in flight; false when it already is, so a second command cannot be sent. */
    private fun begin(id: String): Boolean {
        if (id in mutableState.value.busyLights) return false
        mutableState.update { it.copy(busyLights = it.busyLights + id) }
        return true
    }
}

/** The visible failure text of a read, or null when it returned a value. */
private fun HueResult<*>.failure(): String? = (this as? HueResult.Failure)?.message
