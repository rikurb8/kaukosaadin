package fi.goodconsulting.kaukosaadin.device.hue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
    @Test fun connectReportsConnectingThenConnected() =
        runBlocking {
            val channel = Channel<HueStreamEvent>(Channel.UNLIMITED)
            val connection = HueConnection(this) { channel.receiveAsFlow() }

            connection.connect()
            assertEquals(HueConnectionState.Connecting, connection.state.value)
            channel.send(HueStreamEvent.Open)
            withTimeout(TIMEOUT_MS) { connection.state.first { it == HueConnectionState.Connected } }
            connection.disconnect()
        }

    @Test fun connectedEventsAreRelayedToCollectors() =
        runBlocking {
            val channel = Channel<HueStreamEvent>(Channel.UNLIMITED)
            val connection = HueConnection(this) { channel.receiveAsFlow() }
            val received = Channel<HueEvent>(Channel.UNLIMITED)
            val collector = launch { connection.events.collect { received.send(it) } }
            yield()

            connection.connect()
            channel.send(HueStreamEvent.Open)
            channel.send(HueStreamEvent.Frame("1:0", listOf(SAMPLE_EVENT)))

            assertEquals(SAMPLE_EVENT, withTimeout(TIMEOUT_MS) { received.receive() })
            connection.disconnect()
            withTimeout(TIMEOUT_MS) { collector.cancelAndJoin() }
        }

    @Test fun connectResumesFromTheLastFrameId() =
        runBlocking {
            val requested = mutableListOf<String?>()
            val channel = Channel<HueStreamEvent>(Channel.UNLIMITED)
            val connection =
                HueConnection(this) { from ->
                    requested += from
                    channel.receiveAsFlow()
                }
            val received = Channel<HueEvent>(Channel.UNLIMITED)
            val collector = launch { connection.events.collect { received.send(it) } }
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
            withTimeout(TIMEOUT_MS) { collector.cancelAndJoin() }
        }

    @Test fun aStreamFailureBecomesAFailedStateNotAThrow() =
        runBlocking {
            val connection = HueConnection(this) { flow { throw IOException("no route") } }

            connection.connect()
            val failed = withTimeout(TIMEOUT_MS) { connection.state.first { it is HueConnectionState.Failed } }
            assertEquals(HueErrors.UNREACHABLE, (failed as HueConnectionState.Failed).message)
        }

    @Test fun aFinishedStreamReturnsToDisconnected() =
        runBlocking {
            // Gate the stream's completion so Connected is observed before Disconnected, not raced past.
            val gate = CompletableDeferred<Unit>()
            val connection =
                HueConnection(this) {
                    flow {
                        emit(HueStreamEvent.Open)
                        gate.await()
                    }
                }

            connection.connect()
            withTimeout(TIMEOUT_MS) { connection.state.first { it == HueConnectionState.Connected } }
            gate.complete(Unit)
            withTimeout(TIMEOUT_MS) { connection.state.first { it == HueConnectionState.Disconnected } }
            assertEquals(HueConnectionState.Disconnected, connection.state.value)
        }

    @Test fun connectIsIdempotentWhileLive() =
        runBlocking {
            val calls = AtomicInteger()
            // A gated flow that stays open until the connection is disconnected, so the second connect
            // sees a live job. The gate is completed below; nothing waits on it unboundedly.
            val gate = CompletableDeferred<Unit>()
            val connection =
                HueConnection(this) {
                    calls.incrementAndGet()
                    flow { gate.await() }
                }

            connection.connect()
            connection.connect()
            withTimeout(TIMEOUT_MS) { while (calls.get() < 1) delay(1) }
            assertEquals(1, calls.get())
            gate.complete(Unit)
            connection.disconnect()
        }

    @Test fun disconnectClosesTheStream() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            // The stream parks on a gate until disconnect cancels it; the finally then completes `closed`.
            // The gate is completed at the end so the deferred is not left dangling.
            val gate = CompletableDeferred<Unit>()
            val connection =
                HueConnection(this) {
                    flow {
                        started.complete(Unit)
                        try {
                            gate.await()
                        } finally {
                            closed.complete(Unit)
                        }
                    }
                }

            connection.connect()
            withTimeout(TIMEOUT_MS) { started.await() }
            connection.disconnect()
            withTimeout(TIMEOUT_MS) { closed.await() }
            gate.complete(Unit)
            assertTrue(closed.isCompleted)
            assertEquals(HueConnectionState.Disconnected, connection.state.value)
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        val SAMPLE_EVENT = HueEvent(resourceId = "light-1", resourceType = "light", on = true, brightness = null)
    }
}
