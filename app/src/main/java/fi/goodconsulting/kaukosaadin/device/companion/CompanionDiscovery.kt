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
class CompanionDiscovery(context: Context) {
    data class Device(val name: String, val address: InetAddress, val port: Int)

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val lock = Mutex()

    @Suppress("DEPRECATION") // Serialized resolveService supports the app's API 29 floor.
    suspend fun scan(timeoutMs: Long = 6000): List<Device> = withContext(Dispatchers.IO) {
        require(timeoutMs in 250..15000) { "Discovery timeout must be 250–15000 ms." }
        check(lock.tryLock()) { "Companion discovery already running." }
        val events = Channel<Event>(64)
        val known = linkedMapOf<String, NsdServiceInfo>()
        val pending = linkedMapOf<String, NsdServiceInfo>()
        val devices = linkedMapOf<String, Device>()
        var resolving: NsdManager.ResolveListener? = null
        fun emit(event: Event) {
            if (events.trySend(event).isFailure) events.close(IOException("Too many discovery events; retry on a quieter LAN."))
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { emit(Event.Found(info)) }
            override fun onServiceLost(info: NsdServiceInfo) { emit(Event.Lost(info.serviceName)) }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                events.close(IOException("Companion discovery could not start ($code). Check Wi-Fi/LAN access and retry."))
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {
                events.close(IOException("Companion discovery could not stop ($code)."))
            }
            override fun onDiscoveryStopped(type: String) {
                events.close(IOException("Companion discovery stopped early. Retry."))
            }
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    when (val event = events.receive()) {
                        is Event.Found -> {
                            val info = event.info
                            if (info.serviceType.trimEnd('.') == SERVICE_TYPE && info.serviceName !in known) {
                                check(known.size < 32) { "Too many Companion services; retry on a quieter LAN." }
                                known[info.serviceName] = info
                                pending[info.serviceName] = info
                            }
                        }
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
                        val resolver = object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { emit(Event.Resolved(request, null)) }
                            override fun onServiceResolved(info: NsdServiceInfo) { emit(Event.Resolved(request, info)) }
                        }
                        resolving = resolver
                        nsd.resolveService(request, resolver)
                    }
                }
            }
            devices.values.sortedBy { it.name.lowercase() }
        } finally {
            // Close ingress first: late native callbacks cannot mutate this or a subsequent scan.
            events.cancel()
            runCatching { nsd.stopServiceDiscovery(listener) }
            if (Build.VERSION.SDK_INT >= 34) resolving?.let { runCatching { nsd.stopServiceResolution(it) } }
            // ponytail: API 29–33 cannot cancel a pending native resolution; its late result is discarded.
            lock.unlock()
        }
    }

    private sealed interface Event {
        data class Found(val info: NsdServiceInfo) : Event
        data class Lost(val name: String) : Event
        data class Resolved(val request: NsdServiceInfo, val info: NsdServiceInfo?) : Event
    }

    companion object {
        const val SERVICE_TYPE = "_companion-link._tcp"

        internal fun endpoint(name: String, address: InetAddress?, port: Int): Device? {
            if (address == null || address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return null
            if (port !in 1..65535) return null
            val displayName = name.filterNot { it.isISOControl() }.take(160).ifBlank { "Companion device" }
            return Device(displayName, address, port)
        }
    }
}
