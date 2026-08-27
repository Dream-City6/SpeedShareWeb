package com.alex.speedshare.migration

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

class PeerDiscoveryManager(
    context: Context,
    private val localDeviceId: String,
    private val localDeviceName: String,
    private val servicePort: Int,
    private val appVersion: String,
    private val onPeersChanged: (List<MigrationPeer>) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val peers = ConcurrentHashMap<String, MigrationPeer>()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Synchronized
    fun start() {
        if (registrationListener != null || discoveryListener != null) return
        registerService()
        discoverServices()
    }

    @Synchronized
    fun stop() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        discoveryListener = null
        registrationListener = null
        peers.clear()
        onPeersChanged(emptyList())
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
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
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
                val toRemove = peers.entries.firstOrNull { it.value.name == serviceInfo.serviceName }?.key
                if (toRemove != null) {
                    peers.remove(toRemove)
                    publish()
                }
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Suppress("DEPRECATION")
    private fun resolve(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val attributes = resolved.attributes
                val deviceId = attributes["deviceId"]?.toString(Charsets.UTF_8).orEmpty()
                if (deviceId.isBlank() || deviceId == localDeviceId) return
                val host = resolved.host?.hostAddress.orEmpty()
                if (host.isBlank() || resolved.port <= 0) return
                val peer = MigrationPeer(
                    deviceId = deviceId,
                    name = attributes["name"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                        ?: resolved.serviceName,
                    host = host,
                    port = resolved.port,
                    model = attributes["model"]?.toString(Charsets.UTF_8).orEmpty(),
                    appVersion = attributes["version"]?.toString(Charsets.UTF_8).orEmpty()
                )
                peers[deviceId] = peer
                publish()
            }
        })
    }

    private fun publish() {
        onPeersChanged(
            peers.values.sortedWith(compareBy<MigrationPeer> { it.name.lowercase() }.thenBy { it.deviceId })
        )
    }

    companion object {
        const val SERVICE_TYPE = "_speedshare-migrate._tcp."
    }
}
