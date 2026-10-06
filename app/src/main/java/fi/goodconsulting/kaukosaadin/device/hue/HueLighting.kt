package fi.goodconsulting.kaukosaadin.device.hue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The live-subscription lifecycle a screen renders: not connected, connecting, live, or failed. */
internal sealed interface HueConnectionState {
    data object Disconnected : HueConnectionState

    data object Connecting : HueConnectionState

    data object Connected : HueConnectionState

    data class Failed(
        val message: String,
    ) : HueConnectionState
}

/**
 * Owns the event-stream subscription: [connect] starts it and [disconnect] stops and closes it.
 * Deciding *when* to call them is the screen's (ticket #12); this only provides the mechanism, and it
 * never throws — a stream that cannot open becomes [HueConnectionState.Failed]. There is no reconnect
 * policy here, and no command is queued or replayed.
 */
internal class HueConnection(
    private val scope: CoroutineScope,
    private val openStream: (fromEventId: String?) -> Flow<HueStreamEvent>,
) {
    private val mutableState = MutableStateFlow<HueConnectionState>(HueConnectionState.Disconnected)
    val state: StateFlow<HueConnectionState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<HueEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** State changes delivered while connected, including ones made by switches or other apps. */
    val events: SharedFlow<HueEvent> = mutableEvents.asSharedFlow()

    private var job: Job? = null
    private var lastEventId: String? = null

    /** Starts the subscription; idempotent while already live. [resumeFrom] defaults to the last frame id. */
    @Suppress("TooGenericExceptionCaught") // Single boundary: any stream fault becomes a Failed state, never a throw.
    fun connect(resumeFrom: String? = lastEventId) {
        if (job?.isActive == true) return
        lastEventId = resumeFrom
        mutableState.value = HueConnectionState.Connecting
        job =
            scope.launch {
                try {
                    openStream(resumeFrom).collect { update -> relay(update) }
                    mutableState.value = HueConnectionState.Disconnected
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableState.value = HueConnectionState.Failed(HueErrors.transportFailure(e))
                }
            }
    }

    /** Stops the subscription and closes the stream. Idempotent; the mechanism does not reconnect itself. */
    fun disconnect() {
        job?.cancel()
        job = null
        mutableState.value = HueConnectionState.Disconnected
    }

    private suspend fun relay(update: HueStreamEvent) {
        when (update) {
            HueStreamEvent.Open -> mutableState.value = HueConnectionState.Connected
            is HueStreamEvent.Frame -> {
                update.eventId?.let { lastEventId = it }
                mutableState.value = HueConnectionState.Connected
                for (event in update.events) mutableEvents.emit(event)
            }
        }
    }

    private companion object {
        const val EVENT_BUFFER = 16
    }
}

/**
 * The lighting client tickets #9/#12 consume: the paired bridge's lights, rooms and zones, the on/off
 * and brightness commands, and one live event subscription with its connection state. Every call
 * returns a [HueResult] or a [HueConnectionState]; nothing throws to the screen. It is an interface so
 * a screen's state can be tested against a fake without a physical bridge.
 */
internal interface HueLighting {
    /** The live-subscription state; collect to render connecting, connected or failed. */
    val state: StateFlow<HueConnectionState>

    /** State changes delivered while connected. */
    val events: SharedFlow<HueEvent>

    suspend fun lights(): HueResult<List<HueLight>>

    suspend fun rooms(): HueResult<List<HueGroup>>

    suspend fun zones(): HueResult<List<HueGroup>>

    suspend fun groupedLights(): HueResult<List<HueGroupedLight>>

    suspend fun setOn(
        target: HueCommandTarget,
        on: Boolean,
    ): HueResult<Unit>

    /** Sends brightness alone; see [HueCommands.brightnessBody] for the off-state assumption. */
    suspend fun setBrightness(
        target: HueCommandTarget,
        brightness: Int,
    ): HueResult<Unit>

    /** Opens the event subscription; call from the screen's lifecycle (ticket #12). */
    fun connect()

    /** Closes the event subscription; safe to call when already disconnected. */
    fun disconnect()

    companion object {
        /** The lighting client for a paired [bridge], or null while it has no stored app key. */
        fun of(
            bridge: HueClient,
            scope: CoroutineScope,
        ): HueLighting? {
            val stream = HueEventStream.of(bridge)
            val api = HueApi.of(bridge)
            if (stream == null || api == null) return null
            return BridgeHueLighting(api, HueConnection(scope, stream::updates))
        }
    }
}

/** The bridge-backed [HueLighting]; the interface is what the screen and its tests depend on. */
private class BridgeHueLighting(
    private val api: HueApi,
    private val connection: HueConnection,
) : HueLighting {
    override val state: StateFlow<HueConnectionState> = connection.state

    override val events: SharedFlow<HueEvent> = connection.events

    override suspend fun lights(): HueResult<List<HueLight>> = api.lights()

    override suspend fun rooms(): HueResult<List<HueGroup>> = api.rooms()

    override suspend fun zones(): HueResult<List<HueGroup>> = api.zones()

    override suspend fun groupedLights(): HueResult<List<HueGroupedLight>> = api.groupedLights()

    override suspend fun setOn(
        target: HueCommandTarget,
        on: Boolean,
    ): HueResult<Unit> = api.setOn(target, on)

    override suspend fun setBrightness(
        target: HueCommandTarget,
        brightness: Int,
    ): HueResult<Unit> = api.setBrightness(target, brightness)

    override fun connect() = connection.connect()

    override fun disconnect() = connection.disconnect()
}
