package fi.goodconsulting.kaukosaadin.device.hue

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

/**
 * Read-only mDNS discovery of Hue Bridges on the local network. Advertisements are neither trusted
 * identity nor pairing evidence; the operator still chooses a bridge and presses its link button.
 * Shares [CompanionDiscovery][fi.goodconsulting.kaukosaadin.device.companion.CompanionDiscovery]'s
 * NsdManager pattern, so it needs no multicast lock.
 */
class HueDiscovery(
    context: Context,
) {
    /** A bridge a `_hue._tcp` advertisement resolved to; the model id is optional, unverified metadata. */
    data class Bridge(
        val name: String,
        val address: InetAddress,
        val model: String? = null,
    )

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val lock = Mutex()

    @Suppress("DEPRECATION") // Serialized resolveService supports the app's API 29 floor.
    suspend fun scan(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onFound: (List<Bridge>) -> Unit = {},
    ): List<Bridge> =
        withContext(Dispatchers.IO) {
            require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
                "Discovery timeout must be $MIN_TIMEOUT_MS–$MAX_TIMEOUT_MS ms."
            }
            check(lock.tryLock()) { "Hue discovery already running." }
            val events = Channel<Event>(MAX_PENDING_EVENTS)
            val known = linkedMapOf<String, NsdServiceInfo>()
            val pending = linkedMapOf<String, NsdServiceInfo>()
            val bridges = linkedMapOf<String, Bridge>()
            var resolving: NsdManager.ResolveListener? = null

            fun emit(event: Event) {
                if (events.trySend(event).isFailure) events.close(IOException("Too many discovery events; retry on a quieter LAN."))
            }
            val listener = ServiceListener(emit = ::emit, fail = { events.close(IOException(it)) })
            try {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                withTimeoutOrNull(timeoutMs) {
                    while (true) {
                        when (val event = events.receive()) {
                            is Event.Found -> noteFound(event.info, known, pending)
                            is Event.Lost -> {
                                known.remove(event.name)
                                pending.remove(event.name)
                                bridges.remove(event.name)
                                onFound(bridges.values.toList())
                            }
                            is Event.Resolved -> {
                                resolving = null
                                // A lost/re-found advertisement is a new attempt; ignore the old resolution.
                                if (known[event.request.serviceName] === event.request) {
                                    event.info?.let { info ->
                                        endpoint(event.request.serviceName, info.host, info.port, info.attributes)?.let {
                                            bridges[event.request.serviceName] = it
                                            onFound(bridges.values.toList())
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
                bridges.values.sortedBy { it.name.lowercase() }
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
        check(known.size < MAX_SERVICES) { "Too many Hue services; retry on a quieter LAN." }
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
        ) = fail("Hue discovery could not start ($code). Check Wi-Fi/LAN access and retry.")

        override fun onStopDiscoveryFailed(
            type: String,
            code: Int,
        ) = fail("Hue discovery could not stop ($code).")

        override fun onDiscoveryStopped(type: String) = fail("Hue discovery stopped early. Retry.")
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
        const val SERVICE_TYPE = "_hue._tcp"
        private const val DEFAULT_TIMEOUT_MS = 6000L
        private const val MIN_TIMEOUT_MS = 250L
        private const val MAX_TIMEOUT_MS = 15_000L
        private const val MAX_PENDING_EVENTS = 64
        private const val MAX_SERVICES = 32
        private const val MAX_PORT = 65_535
        private const val MAX_NAME_CHARS = 160
        private const val FALLBACK_NAME = "Hue Bridge"
        private const val MODEL_KEY = "modelid"

        /**
         * Turns a resolved `_hue._tcp` record into a bridge, or null when it cannot be reached. The
         * advertised SRV port is validated but not kept: the API is always reached over HTTPS on 443,
         * never the advertised port. The `modelid` TXT key is unverified, so it is optional display
         * metadata only.
         */
        internal fun endpoint(
            name: String,
            address: InetAddress?,
            port: Int,
            attributes: Map<String, ByteArray> = emptyMap(),
        ): Bridge? {
            if (address == null || address.isUnusableAddress() || port !in 1..MAX_PORT) return null
            return Bridge(
                name = clean(name).ifBlank { FALLBACK_NAME },
                address = address,
                model = txt(attributes, MODEL_KEY),
            )
        }

        private fun txt(
            attributes: Map<String, ByteArray>,
            key: String,
        ): String? =
            attributes.entries
                .firstOrNull { it.key.equals(key, ignoreCase = true) }
                ?.value
                ?.toString(Charsets.UTF_8)
                ?.let(::clean)
                ?.takeIf { it.isNotEmpty() }

        private fun clean(value: String) = value.filterNot { it.isISOControl() }.trim().take(MAX_NAME_CHARS)

        private fun InetAddress.isUnusableAddress() = isAnyLocalAddress || isLoopbackAddress || isMulticastAddress
    }
}
