package fi.goodconsulting.kaukosaadin.device.companion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress

/** Read-only service discovery. Advertisements are neither trusted identity nor pairing evidence. */
class CompanionDiscovery(
    context: Context,
) {
    data class Device(
        val name: String,
        val address: InetAddress,
        val port: Int,
    )

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val lock = Mutex()

    @Suppress("DEPRECATION") // Serialized resolveService supports the app's API 29 floor.
    suspend fun scan(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<Device> =
        withContext(Dispatchers.IO) {
            require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
                "Discovery timeout must be $MIN_TIMEOUT_MS–$MAX_TIMEOUT_MS ms."
            }
            check(lock.tryLock()) { "Companion discovery already running." }
            val events = Channel<Event>(MAX_PENDING_EVENTS)
            val known = linkedMapOf<String, NsdServiceInfo>()
            val pending = linkedMapOf<String, NsdServiceInfo>()
            val devices = linkedMapOf<String, Device>()
            var resolving: NsdManager.ResolveListener? = null

            fun emit(event: Event) {
                if (events.trySend(event).isFailure) events.close(IOException("Too many discovery events; retry on a quieter LAN."))
            }
            val listener =
                ServiceListener(
                    emit = ::emit,
                    fail = { events.close(IOException(it)) },
                )
            try {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                withTimeoutOrNull(timeoutMs) {
                    while (true) {
                        when (val event = events.receive()) {
                            is Event.Found -> noteFound(event.info, known, pending)
                            is Event.Lost -> {
                                known.remove(event.name)
                                pending.remove(event.name)
                                devices.remove(event.name)
                            }
                            is Event.Resolved -> {
                                resolving = null
                                // A lost/re-found advertisement is a new attempt; ignore the old resolution.
                                if (known[event.request.serviceName] === event.request) {
                                    event.info?.let { info ->
                                        endpoint(event.request.serviceName, info.host, info.port)?.let {
                                            devices[event.request.serviceName] = it
                                        }
                                    }
                                }
                            }
                        }
                        if (resolving == null && pending.isNotEmpty()) {
                            val request = pending.entries.first().value
                            pending.remove(request.serviceName)
                            val resolver = ResolveListener(request, emit = ::emit)
                            resolving = resolver
                            nsd.resolveService(request, resolver)
                        }
                    }
                }
                devices.values.sortedBy { it.name.lowercase() }
            } finally {
                teardown(events, listener, resolving)
                lock.unlock()
            }
        }

    /** Close ingress first: late native callbacks cannot mutate this or a subsequent scan. */
    private fun teardown(
        events: Channel<Event>,
        listener: NsdManager.DiscoveryListener,
        resolving: NsdManager.ResolveListener?,
    ) {
        events.cancel()
        runCatching { nsd.stopServiceDiscovery(listener) }
        // ponytail: API 29–33 cannot cancel a pending native resolution; its late result is discarded.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) resolving?.let { runCatching { nsd.stopServiceResolution(it) } }
    }

    /** Remembers a newly advertised service; repeats and over-full scans are dropped. */
    private fun noteFound(
        info: NsdServiceInfo,
        known: MutableMap<String, NsdServiceInfo>,
        pending: MutableMap<String, NsdServiceInfo>,
    ) {
        if (info.serviceType.trimEnd('.') != SERVICE_TYPE || info.serviceName in known) return
        check(known.size < MAX_SERVICES) { "Too many Companion services; retry on a quieter LAN." }
        known[info.serviceName] = info
        pending[info.serviceName] = info
    }

    /** Bridges NsdManager's discovery callbacks onto the scan's event channel. */
    private class ServiceListener(
        private val emit: (Event) -> Unit,
        private val fail: (String) -> Unit,
    ) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit

        override fun onServiceFound(info: NsdServiceInfo) = emit(Event.Found(info))

        override fun onServiceLost(info: NsdServiceInfo) = emit(Event.Lost(info.serviceName))

        override fun onStartDiscoveryFailed(
            type: String,
            code: Int,
        ) = fail("Companion discovery could not start ($code). Check Wi-Fi/LAN access and retry.")

        override fun onStopDiscoveryFailed(
            type: String,
            code: Int,
        ) = fail("Companion discovery could not stop ($code).")

        override fun onDiscoveryStopped(type: String) = fail("Companion discovery stopped early. Retry.")
    }

    /** Reports a resolution attempt back to the scan as an event. */
    private class ResolveListener(
        private val request: NsdServiceInfo,
        private val emit: (Event) -> Unit,
    ) : NsdManager.ResolveListener {
        override fun onResolveFailed(
            info: NsdServiceInfo,
            code: Int,
        ) = emit(Event.Resolved(request, null))

        override fun onServiceResolved(info: NsdServiceInfo) = emit(Event.Resolved(request, info))
    }

    private sealed interface Event {
        data class Found(
            val info: NsdServiceInfo,
        ) : Event

        data class Lost(
            val name: String,
        ) : Event

        data class Resolved(
            val request: NsdServiceInfo,
            val info: NsdServiceInfo?,
        ) : Event
    }

    companion object {
        const val SERVICE_TYPE = "_companion-link._tcp"
        private const val DEFAULT_TIMEOUT_MS = 6000L
        private const val MIN_TIMEOUT_MS = 250L
        private const val MAX_TIMEOUT_MS = 15_000L
        private const val MAX_PENDING_EVENTS = 64
        private const val MAX_SERVICES = 32
        private const val MAX_PORT = 65_535
        private const val MAX_NAME_CHARS = 160

        internal fun endpoint(
            name: String,
            address: InetAddress?,
            port: Int,
        ): Device? {
            if (address == null || address.isUnusableAddress() || port !in 1..MAX_PORT) return null
            val displayName = name.filterNot { it.isISOControl() }.take(MAX_NAME_CHARS).ifBlank { "Companion device" }
            return Device(displayName, address, port)
        }

        private fun InetAddress.isUnusableAddress() = isAnyLocalAddress || isLoopbackAddress || isMulticastAddress
    }
}
