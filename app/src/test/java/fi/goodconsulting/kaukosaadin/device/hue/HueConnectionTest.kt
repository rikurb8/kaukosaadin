package fi.goodconsulting.kaukosaadin.device.hue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class HueConnectionTest {
    @Test fun connectReportsConnectingThenConnected() = runBlocking {
        val channel = Channel<HueStreamEvent>(Channel.UNLIMITED)
        val connection = HueConnection(this) { channel.receiveAsFlow() }

        connection.connect()
        assertEquals(HueConnectionState.Connecting, connection.state.value)
        channel.send(HueStreamEvent.Open)
        withTimeout(TIMEOUT_MS) { connection.state.first { it == HueConnectionState.Connected } }
        connection.disconnect()
    }

    @Test fun connectedEventsAreRelayedToCollectors() = runBlocking {
        val channel = Channel<HueStreamEvent>(Channel.UNLIMITED)
        val connection = HueConnection(this) { channel.receiveAsFlow() }
        val received = Channel<HueEvent>(Channel.UNLIMITED)
        launch { connection.events.collect { received.send(it) } }
        yield()

        connection.connect()
        channel.send(HueStreamEvent.Open)
        channel.send(HueStreamEvent.Frame("1:0", listOf(SAMPLE_EVENT)))

        assertEquals(SAMPLE_EVENT, withTimeout(TIMEOUT_MS) { received.receive() })
        connection.disconnect()
    }

    @Test fun connectResumesFromTheLastFrameId() = runBlocking {
        val requested = mutableListOf<String?>()
        val channel = Channel<HueStreamEvent>(Channel.UNLIMITED)
        val connection = HueConnection(this) { from -> requested += from; channel.receiveAsFlow() }
        val received = Channel<HueEvent>(Channel.UNLIMITED)
        launch { connection.events.collect { received.send(it) } }
        yield()

        connection.connect()
        channel.send(HueStreamEvent.Open)
        channel.send(HueStreamEvent.Frame("5:0", listOf(SAMPLE_EVENT)))
        withTimeout(TIMEOUT_MS) { received.receive() }
        connection.disconnect()

        connection.connect()
        withTimeout(TIMEOUT_MS) { while (requested.size < 2) delay(1) }
        assertEquals(listOf(null, "5:0"), requested)
        connection.disconnect()
    }

    @Test fun aStreamFailureBecomesAFailedStateNotAThrow() = runBlocking {
        val connection = HueConnection(this) { flow { throw IOException("no route") } }

        connection.connect()
        val failed = withTimeout(TIMEOUT_MS) { connection.state.first { it is HueConnectionState.Failed } }
        assertEquals(HueErrors.unreachable(), (failed as HueConnectionState.Failed).message)
    }

    @Test fun aFinishedStreamReturnsToDisconnected() = runBlocking {
        val connection = HueConnection(this) { flowOf(HueStreamEvent.Open) }

        connection.connect()
        withTimeout(TIMEOUT_MS) { connection.state.first { it == HueConnectionState.Connected } }
        withTimeout(TIMEOUT_MS) { connection.state.first { it == HueConnectionState.Disconnected } }
        assertEquals(HueConnectionState.Disconnected, connection.state.value)
    }

    @Test fun connectIsIdempotentWhileLive() = runBlocking {
        val calls = AtomicInteger()
        val connection =
            HueConnection(this) {
                calls.incrementAndGet()
                flow { awaitCancellation() }
            }

        connection.connect()
        connection.connect()
        yield()
        assertEquals(1, calls.get())
        connection.disconnect()
    }

    @Test fun disconnectClosesTheStream() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val connection =
            HueConnection(this) {
                flow {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        closed.complete(Unit)
                    }
                }
            }

        connection.connect()
        withTimeout(TIMEOUT_MS) { started.await() }
        connection.disconnect()
        withTimeout(TIMEOUT_MS) { closed.await() }
        assertTrue(closed.isCompleted)
        assertEquals(HueConnectionState.Disconnected, connection.state.value)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        val SAMPLE_EVENT = HueEvent(action = "update", resourceId = "light-1", resourceType = "light", on = true, brightness = null)
    }
}
