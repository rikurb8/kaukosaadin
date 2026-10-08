package fi.goodconsulting.kaukosaadin.ui

import fi.goodconsulting.kaukosaadin.device.hue.HueCommandTarget
import fi.goodconsulting.kaukosaadin.device.hue.HueConnectionState
import fi.goodconsulting.kaukosaadin.device.hue.HueEvent
import fi.goodconsulting.kaukosaadin.device.hue.HueFavorites
import fi.goodconsulting.kaukosaadin.device.hue.HueGroup
import fi.goodconsulting.kaukosaadin.device.hue.HueGroupedLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLight
import fi.goodconsulting.kaukosaadin.device.hue.HueLighting
import fi.goodconsulting.kaukosaadin.device.hue.HueProtocol
import fi.goodconsulting.kaukosaadin.device.hue.HueResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Shown when a favorite could not be written; the list stays as it was stored, so nothing is claimed that is not there. */
private const val FAVORITES_FAILURE = "Could not save the favorites on this phone."

/** The brightness the Super remote's Bright preset writes. */
private const val BRIGHT_BRIGHTNESS = 100

/** The brightness the Super remote's Dim preset writes; unlike the lighting screen's slider, Dim turns an off target on. */
private const val DIM_BRIGHTNESS = 20

/**
 * The Super remote's lighting presets: Bright turns the target on at 100%, Dim on at 20% and Off
 * turns it off. The label is what the button reads, so the preset is named in one place.
 */
internal enum class LightingPreset(
    val label: String,
) {
    Bright("Bright"),
    Dim("Dim"),
    Off("Off"),
}

/** What the lighting screen renders for the selected bridge: its rooms, zones, lights and grouped lights, and any failure. */
internal data class LightingState(
    val rooms: List<HueGroup> = emptyList(),
    val zones: List<HueGroup> = emptyList(),
    val lights: List<HueLight> = emptyList(),
    /** The bridge's grouped lights; a room or zone is controlled as a unit through the one matching its [HueGroup.groupedLightId]. */
    val groupedLights: List<HueGroupedLight> = emptyList(),
    /** The light ids the operator kept, by id so a rename on the bridge follows by itself. */
    val favoriteLights: Set<String> = emptySet(),
    /** The room and zone ids the operator kept, by id for the same reason. */
    val favoriteGroups: Set<String> = emptySet(),
    val loading: Boolean = true,
    /** The most recent failure to show, or null. A failed load or command surfaces it; a success clears it. */
    val failure: String? = null,
    /** Target ids with an operation in flight — a preset, a command or a reconnect — whose controls are disabled meanwhile. */
    val busyTargets: Set<String> = emptySet(),
    /** The slider value the operator is dragging, per target id. A draft is not a command, so it is never sent. */
    val brightnessDrafts: Map<String, Int> = emptyMap(),
)

/**
 * The lighting screen's state and commands, kept outside Compose so the screen only renders what
 * this derives. [load] fetches the bridge's lights, rooms, zones and grouped lights when the screen
 * opens. [toggle] sends one on/off command per light, or per room's or zone's grouped light so a
 * group moves as a unit.
 * [dragBrightness] only records the slider under the operator's finger and [releaseBrightness] sends
 * what it recorded, exactly once, when the operator lets go. Brightness is never sent for a target
 * that is off, so adjusting it cannot turn anything on. A failed read or command becomes
 * [LightingState.failure]; nothing throws, and no command is queued or replayed. [toggleFavorite]
 * keeps a light, room or zone among the favorites, written to the bridge's own storage, and the
 * screen lists those first through [LightingState.orderedLights], [LightingState.orderedRooms] and
 * [LightingState.orderedZones] without hiding or repeating any of them. [applyPreset] is the Super
 * remote's one-tap Bright/Dim/Off for a room's or zone's grouped light and [reconnect] reopens the
 * bridge's subscription and reads it again. [live] is the screen's
 * visible lifetime: it refreshes [state] on entry and merges the bridge's own changes into it until
 * it is cancelled, so a switch or another app shows up here without ever becoming a command.
 */
@Suppress("TooManyFunctions") // The lighting screen's state, its commands and the Super remote's presets share one busy/failure owner.
internal class LightingController(
    private val lighting: HueLighting,
    /** The phone's favorites, or null for a caller that has no favorites to keep, like the Super remote's presets. */
    private val favorites: HueFavorites? = null,
) {
    private val mutableState =
        MutableStateFlow(
            LightingState(
                favoriteLights = favorites?.lightIds ?: emptySet(),
                favoriteGroups = favorites?.groupIds ?: emptySet(),
            ),
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
     * Fetches lights, rooms, zones and grouped lights; [live] calls it on every entry to the screen, so
     * coming back reads the bridge again. A failed read keeps what the others returned.
     */
    suspend fun load() {
        mutableState.update { it.copy(loading = true, failure = null) }
        val lights = lighting.lights()
        val rooms = lighting.rooms()
        val zones = lighting.zones()
        val groupedLights = lighting.groupedLights()
        mutableState.update {
            it.copy(
                loading = false,
                lights = (lights as? HueResult.Ok)?.value ?: it.lights,
                rooms = (rooms as? HueResult.Ok)?.value ?: it.rooms,
                zones = (zones as? HueResult.Ok)?.value ?: it.zones,
                groupedLights = (groupedLights as? HueResult.Ok)?.value ?: it.groupedLights,
                failure = lights.failure() ?: rooms.failure() ?: zones.failure() ?: groupedLights.failure(),
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
        sendOn(HueCommandTarget.Light(light.id), !current.on)
    }

    /** Sends one on/off command for [group]'s grouped light; a room or zone the bridge reports no grouped light for is left alone. */
    suspend fun toggle(group: HueGroup) {
        val light = mutableState.value.groupedLightFor(group) ?: return
        sendOn(HueCommandTarget.Group(light.id), !light.on)
    }

    /**
     * Records [brightness] as the value under the operator's finger while they drag the slider for
     * [target]. It sends nothing: a drag becomes a command only in [releaseBrightness], so one
     * completed interaction is exactly one command. A target that is off keeps no draft at all.
     */
    fun dragBrightness(
        target: HueCommandTarget,
        brightness: Int,
    ) {
        if (!mutableState.value.brightnessEnabled(target)) return
        mutableState.update { it.copy(brightnessDrafts = it.brightnessDrafts + (target.id to brightness)) }
    }

    /**
     * Sends one command for [preset] to [target]: Bright and Dim turn it on and set their brightness
     * in one combined request, and Off turns it off. A tap while a command for [target] is in flight
     * is dropped, so one tap is at most one command. Nothing here records which preset was applied:
     * what the section shows comes from the bridge through [live] and [load].
     */
    suspend fun applyPreset(
        target: HueCommandTarget,
        preset: LightingPreset,
    ) {
        if (!begin(target.id)) return
        try {
            val result =
                when (preset) {
                    LightingPreset.Bright -> lighting.setOnWithBrightness(target, BRIGHT_BRIGHTNESS)
                    LightingPreset.Dim -> lighting.setOnWithBrightness(target, DIM_BRIGHTNESS)
                    LightingPreset.Off -> lighting.setOn(target, false)
                }
            mutableState.update { state ->
                if (result is HueResult.Failure) state.copy(failure = result.message) else state.copy(failure = null)
            }
        } finally {
            mutableState.update { it.copy(busyTargets = it.busyTargets - target.id) }
        }
    }

    /**
     * Re-opens the bridge's live subscription and reads its state again, for the section's Reconnect
     * control: readiness and state are restored and nothing is sent — no preset, brightness or on/off.
     * It shares [applyPreset]'s guard for [target], so it never runs while a preset is in flight and a
     * duplicate tap is dropped.
     */
    suspend fun reconnect(target: HueCommandTarget) {
        if (!begin(target.id)) return
        try {
            lighting.connect()
            load()
        } finally {
            mutableState.update { it.copy(busyTargets = it.busyTargets - target.id) }
        }
    }

    /**
     * Sends one brightness command for the value [dragBrightness] last recorded for [target], and
     * clears the draft. The screen calls this when the operator releases the slider; dragging itself
     * never emits. A target that is off keeps no draft and is left alone, so brightness alone is
     * written and nothing is turned on. A release while one is already in flight for [target] is dropped.
     */
    suspend fun releaseBrightness(target: HueCommandTarget) {
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
        target: HueCommandTarget,
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
        toggleFavorite(light.id, group = false)
    }

    /** Keeps [group] among the favorites, or drops it when it already is, on the same terms as the light. */
    fun toggleFavorite(group: HueGroup) {
        toggleFavorite(group.id, group = true)
    }

    private fun toggleFavorite(
        id: String,
        group: Boolean,
    ) {
        // A preset-only caller has no favorites list to keep, so there is nothing to change.
        val store = favorites ?: return
        val stored = if (group) store.toggleGroup(id) else store.toggleLight(id)
        mutableState.update {
            if (stored) {
                it.copy(failure = null, favoriteLights = store.lightIds, favoriteGroups = store.groupIds)
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
internal fun LightingState.brightnessEnabled(target: HueCommandTarget): Boolean =
    target.id !in busyTargets &&
        when (target) {
            is HueCommandTarget.Light -> lights.firstOrNull { it.id == target.id }?.on == true
            is HueCommandTarget.Group -> groupedLights.firstOrNull { it.id == target.id }?.on == true
        }

/** The grouped light that carries [group]'s on/off and brightness, or null when the group has no controllable light. */
internal fun LightingState.groupedLightFor(group: HueGroup): HueGroupedLight? =
    group.groupedLightId?.let { id -> groupedLights.firstOrNull { it.id == id } }

/** The bridge's lights with the operator's favorites first; every light the bridge reported is listed exactly once. */
internal val LightingState.orderedLights: List<HueLight>
    get() = favoritesFirst(lights, favoriteLights) { it.id }

/** [group]'s lights, favorites first, in the bridge's order otherwise; a light can sit in one room and several zones. */
internal fun LightingState.lightsIn(group: HueGroup): List<HueLight> = orderedLights.filter(group::contains)

/** The lights no room claims, favorites first, so a light outside every room is still listed somewhere. */
internal val LightingState.lightsOutsideRooms: List<HueLight>
    get() = orderedLights.filter { light -> rooms.none { it.contains(light) } }

/** The bridge's rooms with the operator's favorites first; every room the bridge reported is listed exactly once. */
internal val LightingState.orderedRooms: List<HueGroup>
    get() = favoritesFirst(rooms, favoriteGroups) { it.id }

/** The bridge's zones with the operator's favorites first; every zone the bridge reported is listed exactly once. */
internal val LightingState.orderedZones: List<HueGroup>
    get() = favoritesFirst(zones, favoriteGroups) { it.id }

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
