package com.alex.speedshare.migration

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class PeerDiscoveryManager(
    context: Context,
    private val localDeviceId: String,
    private val localDeviceName: String,
    private val servicePort: Int,
    private val appVersion: String,
    private val onPeersChanged: (List<MigrationPeer>) -> Unit
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val peers = ConcurrentHashMap<String, MigrationPeer>()
    private val serviceToDeviceId = ConcurrentHashMap<String, String>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val fallbackExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "SpeedShare-DiscoveryFallback").apply { isDaemon = true }
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var fallbackRunning = false
    private var fallbackSocket: DatagramSocket? = null

    @Synchronized
    fun start() {
        if (registrationListener != null || discoveryListener != null || fallbackRunning) return
        MigrationLocalEndpointRegistry.update(servicePort)
        acquireMulticastLock()
        registerService()
        discoverServices()
        startUdpFallback()
    }

    @Synchronized
    fun stop() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        discoveryListener = null
        registrationListener = null
        fallbackRunning = false
        runCatching { fallbackSocket?.close() }
        fallbackSocket = null
        peers.clear()
        serviceToDeviceId.clear()
        lastSeen.clear()
        multicastLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        multicastLock = null
        MigrationLocalEndpointRegistry.clear(servicePort)
        onPeersChanged(emptyList())
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager?.createMulticastLock("SpeedShareWeb:MigrationDiscovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = "SpeedShare-${localDeviceName.take(20)}-${localDeviceId.takeLast(4)}"
            serviceType = SERVICE_TYPE
            port = servicePort
            setAttribute("deviceId", localDeviceId)
            setAttribute("name", localDeviceName)
            setAttribute("version", appVersion)
            setAttribute("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            setAttribute("sdk", Build.VERSION.SDK_INT.toString())
            setAttribute("abis", Build.SUPPORTED_ABIS.joinToString(","))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun discoverServices() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val deviceId = serviceToDeviceId.remove(serviceInfo.serviceName) ?: return
                if (System.currentTimeMillis() - (lastSeen[deviceId] ?: 0L) > PEER_STALE_MS) {
                    peers.remove(deviceId)
                    lastSeen.remove(deviceId)
                    publish()
                }
            }
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    @Suppress("DEPRECATION")
    private fun resolve(serviceInfo: NsdServiceInfo) {
        runCatching {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val attributes = resolved.attributes
                    val deviceId = attributes["deviceId"]?.toString(Charsets.UTF_8).orEmpty()
                    if (deviceId.isBlank() || deviceId == localDeviceId) return
                    val host = resolved.host?.hostAddress.orEmpty()
                    if (host.isBlank() || resolved.port <= 0) return
                    putPeer(
                        MigrationPeer(
                            deviceId = deviceId,
                            name = attributes["name"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                                ?: resolved.serviceName,
                            host = host,
                            port = resolved.port,
                            model = attributes["model"]?.toString(Charsets.UTF_8).orEmpty(),
                            appVersion = attributes["version"]?.toString(Charsets.UTF_8).orEmpty(),
                            androidSdk = attributes["sdk"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0,
                            supportedAbis = attributes["abis"]?.toString(Charsets.UTF_8)
                                ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
                        )
                    )
                    serviceToDeviceId[resolved.serviceName] = deviceId
                }
            })
        }
    }

    private fun startUdpFallback() {
        fallbackRunning = true
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1500
                bind(InetSocketAddress(UDP_PORT))
            }
        }.getOrNull()
        if (socket == null) {
            fallbackRunning = false
            return
        }
        fallbackSocket = socket

        fallbackExecutor.execute {
            val buffer = ByteArray(4096)
            while (fallbackRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    if (json.optString("magic") != UDP_MAGIC) continue
                    val deviceId = json.optString("deviceId")
                    val port = json.optInt("port")
                    if (deviceId.isBlank() || deviceId == localDeviceId || port !in 1..65535) continue
                    putPeer(
                        MigrationPeer(
                            deviceId = deviceId,
                            name = json.optString("name", "SpeedShare"),
                            host = packet.address.hostAddress.orEmpty(),
                            port = port,
                            model = json.optString("model"),
                            appVersion = json.optString("version"),
                            androidSdk = json.optInt("sdk", 0),
                            supportedAbis = json.optString("abis").split(',').filter { it.isNotBlank() }
                        )
                    )
                } catch (_: SocketTimeoutException) {
                    removeStalePeers()
                } catch (_: Throwable) {
                    if (!fallbackRunning) break
                }
            }
        }

        fallbackExecutor.execute {
            val payload = JSONObject()
                .put("magic", UDP_MAGIC)
                .put("deviceId", localDeviceId)
                .put("name", localDeviceName)
                .put("port", servicePort)
                .put("version", appVersion)
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                .put("sdk", Build.VERSION.SDK_INT)
                .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
                .toString()
                .toByteArray(Charsets.UTF_8)
            while (fallbackRunning) {
                broadcastAddresses().forEach { address ->
                    runCatching { socket.send(DatagramPacket(payload, payload.size, address, UDP_PORT)) }
                }
                removeStalePeers()
                try {
                    Thread.sleep(2_000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val addresses = linkedSetOf<InetAddress>()
        runCatching { addresses += InetAddress.getByName("255.255.255.255") }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                network.interfaceAddresses.mapNotNullTo(addresses) { it.broadcast }
            }
        }
        return addresses
    }

    private fun putPeer(peer: MigrationPeer) {
        if (peer.deviceId == localDeviceId || peer.host.isBlank() || peer.port <= 0) return
        peers[peer.deviceId] = peer
        lastSeen[peer.deviceId] = System.currentTimeMillis()
        publish()
    }

    private fun removeStalePeers() {
        val now = System.currentTimeMillis()
        var changed = false
        lastSeen.entries.forEach { entry ->
            if (now - entry.value > PEER_STALE_MS) {
                lastSeen.remove(entry.key)
                peers.remove(entry.key)
                changed = true
            }
        }
        if (changed) publish()
    }

    private fun publish() {
        onPeersChanged(
            peers.values.sortedWith(compareBy<MigrationPeer> { it.name.lowercase() }.thenBy { it.deviceId })
        )
    }

    companion object {
        const val SERVICE_TYPE = "_speedshare-migrate._tcp."
        private const val UDP_PORT = 47999
        private const val UDP_MAGIC = "speedshare-migration-v2"
        private const val PEER_STALE_MS = 8_000L
    }
}
