package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.hue.HueConnectionState
import fi.goodconsulting.kaukosaadin.device.hue.HueEvent
import fi.goodconsulting.kaukosaadin.device.hue.HueFavorites
import fi.goodconsulting.kaukosaadin.device.hue.HueGroupedLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import fi.goodconsulting.kaukosaadin.device.hue.HueProtocol
import fi.goodconsulting.kaukosaadin.device.hue.HueResult
import fi.goodconsulting.kaukosaadin.device.hue.HueRoom
import fi.goodconsulting.kaukosaadin.device.hue.HueTarget
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Shown when a favorite could not be written; the list stays as it was stored, so nothing is claimed that is not there. */
private const val FAVORITES_FAILURE = "Could not save the favorites on this phone."

/** What the lighting screen renders for the selected bridge: its rooms, lights and grouped lights, and any failure. */
internal data class LightingState(
    val rooms: List<HueRoom> = emptyList(),
    val lights: List<HueLight> = emptyList(),
    /** The bridge's grouped lights; a room is controlled as a unit through the one matching its [HueRoom.groupedLightId]. */
    val groupedLights: List<HueGroupedLight> = emptyList(),
    /** The light ids the operator kept, by id so a rename on the bridge follows by itself. */
    val favoriteLights: Set<String> = emptySet(),
    /** The room ids the operator kept, by id for the same reason. */
    val favoriteRooms: Set<String> = emptySet(),
    val loading: Boolean = true,
    /** The most recent failure to show, or null. A failed load or command surfaces it; a success clears it. */
    val failure: String? = null,
    /** Target ids with a command in flight: a light id, or a room's grouped-light id. Their controls are disabled meanwhile. */
    val busyTargets: Set<String> = emptySet(),
    /** The slider value the operator is dragging, per target id. A draft is not a command, so it is never sent. */
    val brightnessDrafts: Map<String, Int> = emptyMap(),
)

/**
 * The lighting screen's state and commands, kept outside Compose so the screen only renders what
 * this derives. [load] fetches the bridge's lights, rooms and grouped lights when the screen opens.
 * [toggle] sends one on/off command per light, or per room's grouped light so a room moves as a unit.
 * [dragBrightness] only records the slider under the operator's finger and [releaseBrightness] sends
 * what it recorded, exactly once, when the operator lets go. Brightness is never sent for a target
 * that is off, so adjusting it cannot turn anything on. A failed read or command becomes
 * [LightingState.failure]; nothing throws, and no command is queued or replayed. [toggleFavorite]
 * keeps a light or room among the favorites, written to the bridge's own storage, and the screen
 * lists those first through [LightingState.orderedLights] and [LightingState.orderedRooms] without
 * hiding or repeating any of them. [live] is the screen's visible lifetime: it refreshes [state] on
 * entry and merges the bridge's own changes into it until it is cancelled, so a switch or another
 * app shows up here without ever becoming a command.
 */
internal class LightingController(
    private val lighting: HueLighting,
    private val favorites: HueFavorites,
) {
    private val mutableState =
        MutableStateFlow(
            LightingState(favoriteLights = favorites.lightIds, favoriteRooms = favorites.roomIds),
        )

    /** Everything the lighting screen shows. */
    val state: StateFlow<LightingState> = mutableState.asStateFlow()

    /** The bridge's live-subscription state; the screen renders its failure. */
    val connection: StateFlow<HueConnectionState> = lighting.state

    /**
     * Runs for as long as the lighting screen is visible: refreshes the bridge's state on entry, then
     * applies the bridge's own changes until the caller cancels this. Cancelling it — leaving the
     * screen or backgrounding the app — closes the live subscription in a `finally`, so the next entry
     * connects and reads the bridge again. Nothing here sends a command.
     */
    suspend fun live() {
        try {
            lighting.connect()
            load()
            lighting.events.collect { event -> mutableState.update { it.updatedBy(event) } }
        } finally {
            withContext(NonCancellable) { lighting.disconnect() }
        }
    }

    /**
     * Fetches lights, rooms and grouped lights; [live] calls it on every entry to the screen, so
     * coming back reads the bridge again. A failed read keeps what the others returned.
     */
    suspend fun load() {
        mutableState.update { it.copy(loading = true, failure = null) }
        val lights = lighting.lights()
        val rooms = lighting.rooms()
        val groupedLights = lighting.groupedLights()
        mutableState.update {
            it.copy(
                loading = false,
                lights = (lights as? HueResult.Ok)?.value ?: it.lights,
                rooms = (rooms as? HueResult.Ok)?.value ?: it.rooms,
                groupedLights = (groupedLights as? HueResult.Ok)?.value ?: it.groupedLights,
                failure = lights.failure() ?: rooms.failure() ?: groupedLights.failure(),
            )
        }
    }

    /**
     * Sends one on/off command for [light]; a tap while that light's command is in flight is ignored.
     * The value to send is the opposite of what the bridge last reported for the light, read here at
     * send time: a live event that changed the light meanwhile would make the rendered [light] lie.
     */
    suspend fun toggle(light: HueLight) {
        val current = mutableState.value.lights.firstOrNull { it.id == light.id } ?: return
        sendOn(HueTarget.Light(light.id), !current.on)
    }

    /** Sends one on/off command for [room]'s grouped light; a room the bridge reports no grouped light for is left alone. */
    suspend fun toggle(room: HueRoom) {
        val group = mutableState.value.groupedLightFor(room) ?: return
        sendOn(HueTarget.Group(group.id), !group.on)
    }

    /**
     * Records [brightness] as the value under the operator's finger while they drag the slider for
     * [target]. It sends nothing: a drag becomes a command only in [releaseBrightness], so one
     * completed interaction is exactly one command. A target that is off keeps no draft at all.
     */
    fun dragBrightness(
        target: HueTarget,
        brightness: Int,
    ) {
        if (!mutableState.value.brightnessEnabled(target)) return
        mutableState.update { it.copy(brightnessDrafts = it.brightnessDrafts + (target.id to brightness)) }
    }

    /**
     * Sends one brightness command for the value [dragBrightness] last recorded for [target], and
     * clears the draft. The screen calls this when the operator releases the slider; dragging itself
     * never emits. A target that is off keeps no draft and is left alone, so brightness alone is
     * written and nothing is turned on. A release while one is already in flight for [target] is dropped.
     */
    suspend fun releaseBrightness(target: HueTarget) {
        val brightness = mutableState.value.brightnessDrafts[target.id]
        if (brightness == null || !mutableState.value.brightnessEnabled(target)) {
            mutableState.update { it.copy(brightnessDrafts = it.brightnessDrafts - target.id) }
            return
        }
        if (!begin(target.id)) return
        try {
            when (val result = lighting.setBrightness(target, brightness)) {
                is HueResult.Ok ->
                    mutableState.update {
                        it.copy(
                            failure = null,
                            brightnessDrafts = it.brightnessDrafts - target.id,
                            lights =
                                it.lights.map { entry ->
                                    if (entry.id == target.id) entry.copy(brightness = brightness.toDouble()) else entry
                                },
                            groupedLights =
                                it.groupedLights.map { entry ->
                                    if (entry.id == target.id) entry.copy(brightness = brightness.toDouble()) else entry
                                },
                        )
                    }
                is HueResult.Failure ->
                    mutableState.update { it.copy(failure = result.message, brightnessDrafts = it.brightnessDrafts - target.id) }
            }
        } finally {
            mutableState.update { it.copy(busyTargets = it.busyTargets - target.id) }
        }
    }

    /** Sends one on/off command for [target], mirroring an accepted value into the list it belongs to. */
    private suspend fun sendOn(
        target: HueTarget,
        on: Boolean,
    ) {
        if (!begin(target.id)) return
        try {
            when (val result = lighting.setOn(target, on)) {
                is HueResult.Ok ->
                    mutableState.update {
                        it.copy(
                            failure = null,
                            lights = it.lights.map { entry -> if (entry.id == target.id) entry.copy(on = on) else entry },
                            groupedLights =
                                it.groupedLights.map { entry ->
                                    if (entry.id == target.id) entry.copy(on = on) else entry
                                },
                        )
                    }
                is HueResult.Failure -> mutableState.update { it.copy(failure = result.message) }
            }
        } finally {
            mutableState.update { it.copy(busyTargets = it.busyTargets - target.id) }
        }
    }

    /** Marks [id] in flight; false when it already is, so a second command cannot be sent. */
    private fun begin(id: String): Boolean {
        if (id in mutableState.value.busyTargets) return false
        mutableState.update { it.copy(busyTargets = it.busyTargets + id) }
        return true
    }

    /**
     * Keeps [light] among the favorites, or drops it when it already is. The change is written to the
     * bridge's storage before it is rendered, so the favorites shown are the ones a restart will find;
     * a write that does not stick keeps the previous list and becomes [LightingState.failure].
     */
    fun toggleFavorite(light: HueLight) {
        toggleFavorite(light.id, room = false)
    }

    /** Keeps [room] among the favorites, or drops it when it already is, on the same terms as the light. */
    fun toggleFavorite(room: HueRoom) {
        toggleFavorite(room.id, room = true)
    }

    private fun toggleFavorite(
        id: String,
        room: Boolean,
    ) {
        val stored = if (room) favorites.toggleRoom(id) else favorites.toggleLight(id)
        mutableState.update {
            if (stored) {
                it.copy(failure = null, favoriteLights = favorites.lightIds, favoriteRooms = favorites.roomIds)
            } else {
                it.copy(failure = FAVORITES_FAILURE)
            }
        }
    }
}

/** The visible failure text of a read, or null when it returned a value. */
private fun HueResult<*>.failure(): String? = (this as? HueResult.Failure)?.message

/**
 * The message the lighting screen's failure banner shows: a broken live subscription first, then the
 * last read or command failure. [LightingController.live] is what makes the connection failure reach
 * the banner at all.
 */
internal fun failureMessage(
    connection: HueConnectionState,
    state: LightingState,
): String? = (connection as? HueConnectionState.Failed)?.message ?: state.failure

/**
 * [this] with the change [event] carries applied: only the resource the event names changes, and only
 * the fields the event carries. An event for a resource the screen does not track, or a type it does
 * not show, changes nothing.
 */
private fun LightingState.updatedBy(event: HueEvent): LightingState =
    when (event.resourceType) {
        HueProtocol.LIGHT_RESOURCE -> copy(lights = lights.map { it.updatedBy(event) })
        HueProtocol.GROUPED_LIGHT_RESOURCE -> copy(groupedLights = groupedLights.map { it.updatedBy(event) })
        else -> this
    }

/** [this] with the on/off and brightness [event] carries, when the event names this light. */
private fun HueLight.updatedBy(event: HueEvent): HueLight =
    if (id == event.resourceId) copy(on = event.on ?: on, brightness = event.brightness ?: brightness) else this

/** [this] with the on/off and brightness [event] carries, when the event names this grouped light. */
private fun HueGroupedLight.updatedBy(event: HueEvent): HueGroupedLight =
    if (id == event.resourceId) copy(on = event.on ?: on, brightness = event.brightness ?: brightness) else this

/**
 * Whether the operator may adjust [target]'s brightness: only while the bridge reports it as on and
 * no command is in flight for it. This is the spec's off-state rule, in one place: the screen disables
 * the slider with it, and the controller refuses a drag or release for anything it refuses here.
 */
internal fun LightingState.brightnessEnabled(target: HueTarget): Boolean =
    target.id !in busyTargets &&
        when (target) {
            is HueTarget.Light -> lights.firstOrNull { it.id == target.id }?.on == true
            is HueTarget.Group -> groupedLights.firstOrNull { it.id == target.id }?.on == true
        }

/** The grouped light that carries [room]'s on/off and brightness, or null when the room has no controllable group. */
internal fun LightingState.groupedLightFor(room: HueRoom): HueGroupedLight? =
    room.groupedLightId?.let { id -> groupedLights.firstOrNull { it.id == id } }

/** The bridge's lights with the operator's favorites first; every light the bridge reported is listed exactly once. */
internal val LightingState.orderedLights: List<HueLight>
    get() = favoritesFirst(lights, favoriteLights) { it.id }

/** The bridge's rooms with the operator's favorites first; every room the bridge reported is listed exactly once. */
internal val LightingState.orderedRooms: List<HueRoom>
    get() = favoritesFirst(rooms, favoriteRooms) { it.id }

/**
 * [items] with the ones [favorites] names first, each part keeping the bridge's own order. This splits
 * the one list rather than building two, so nothing is dropped and nothing is listed twice, and a kept
 * id the bridge no longer reports matches no item — it is in neither part, so it is never rendered.
 */
private fun <T> favoritesFirst(
    items: List<T>,
    favorites: Set<String>,
    id: (T) -> String,
): List<T> {
    val (kept, rest) = items.partition { id(it) in favorites }
    return kept + rest
}
