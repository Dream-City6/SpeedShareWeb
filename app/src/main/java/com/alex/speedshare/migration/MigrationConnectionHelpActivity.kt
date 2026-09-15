package com.alex.speedshare.migration

import android.content.ClipData
import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.alex.speedshare.AppSettings
import com.alex.speedshare.Localization
import com.alex.speedshare.Translator
import com.alex.speedshare.createQrCodeBitmap
import java.net.Inet4Address
import java.net.DatagramSocket
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DirectHotspotState(
    val starting: Boolean = false,
    val active: Boolean = false,
    val ssid: String = "",
    val password: String = "",
    val security: String = "WPA",
    val messageKey: String = "",
    val permanentlyDenied: Boolean = false,
    val pairToken: String = "",
    val pairTokenDeviceId: String = "",
    val networkRevision: Long = 0L
)

internal object MigrationDirectHotspot {
    private val _state = MutableStateFlow(DirectHotspotState())
    val state = _state.asStateFlow()
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    private fun publish(state: DirectHotspotState) {
        _state.value = state.copy(networkRevision = _state.value.networkRevision + 1L)
    }

    fun start(context: Context) {
        if (_state.value.starting || _state.value.active) return
        publish(DirectHotspotState(starting = true, messageKey = "migration_hotspot_starting_status"))
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            publish(DirectHotspotState(messageKey = "migration_hotspot_permission_required"))
            return
        }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        runCatching {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                    reservation?.close()
                    reservation = value
                    val credentials = hotspotCredentials(value)
                    publish(DirectHotspotState(
                        active = true,
                        ssid = credentials.ssid,
                        password = credentials.password,
                        security = credentials.security,
                        pairToken = UUID.randomUUID().toString(),
                        messageKey = "migration_hotspot_waiting"
                    ))
                    ResilientMigrationController.get(context).refreshNetworkState()
                }
                override fun onStopped() {
                    reservation = null
                    publish(DirectHotspotState(messageKey = "migration_hotspot_stopped"))
                    ResilientMigrationController.get(context).refreshNetworkState()
                }
                override fun onFailed(reason: Int) {
                    publish(DirectHotspotState(messageKey = when (reason) {
                        ERROR_NO_CHANNEL -> "migration_hotspot_error_channel"
                        ERROR_INCOMPATIBLE_MODE -> "migration_hotspot_error_mode"
                        ERROR_TETHERING_DISALLOWED -> "migration_hotspot_error_disallowed"
                        else -> "migration_hotspot_error_generic"
                    }))
                }
            }, null)
        }.onFailure {
            publish(DirectHotspotState(messageKey = "migration_hotspot_error_unsupported"))
        }
    }

    fun stop() {
        reservation?.close()
        reservation = null
        publish(DirectHotspotState(messageKey = "migration_hotspot_stopped"))
    }

    fun permissionRequired(permanentlyDenied: Boolean = false) {
        publish(
            DirectHotspotState(
                messageKey = if (permanentlyDenied) "migration_hotspot_permission_settings" else "migration_hotspot_permission_required",
                permanentlyDenied = permanentlyDenied
            )
        )
    }

    fun notifyNetworkChanged() {
        _state.value = _state.value.copy(networkRevision = _state.value.networkRevision + 1L)
    }

    @Synchronized
    fun claimPairToken(token: String, deviceId: String): Boolean {
        val current = _state.value
        if (!canClaimPairToken(current.active, current.pairToken, current.pairTokenDeviceId, token, deviceId)) {
            return false
        }
        if (current.pairTokenDeviceId.isBlank()) {
            _state.value = current.copy(
                pairTokenDeviceId = deviceId,
                networkRevision = current.networkRevision + 1L
            )
        }
        return true
    }
}

internal data class DirectWifiConnectionState(
    val requesting: Boolean = false,
    val connected: Boolean = false,
    val ssid: String = "",
    val messageKey: String = "",
    val detail: String = "",
    val canRetry: Boolean = false
)

/** Keeps the requested local-only Wi-Fi network alive for the whole migration session. */
internal object MigrationDirectWifiConnector {
    private val _state = MutableStateFlow(DirectWifiConnectionState())
    val state = _state.asStateFlow()
    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastCredentials: WifiQrCredentials? = null
    @Volatile private var boundNetwork: Network? = null

    fun connect(context: Context, credentials: WifiQrCredentials) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _state.value = DirectWifiConnectionState(
                ssid = credentials.ssid,
                messageKey = "migration_wifi_request_unsupported",
                detail = "Android ${Build.VERSION.SDK_INT} does not support WifiNetworkSpecifier"
            )
            openSettings(context, Settings.ACTION_WIFI_SETTINGS)
            return
        }
        releaseConnection(context, clearCredentials = false)
        lastCredentials = credentials
        _state.value = DirectWifiConnectionState(
            requesting = true,
            ssid = credentials.ssid,
            messageKey = "migration_wifi_requesting"
        )
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val request = runCatching {
            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(credentials.ssid)
            when (credentials.security) {
                "WPA3" -> specifierBuilder.setWpa3Passphrase(credentials.password)
                "WPA", "WPA2" -> specifierBuilder.setWpa2Passphrase(credentials.password)
                "OWE" -> specifierBuilder.setIsEnhancedOpen(true)
            }
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()
        }.getOrElse {
            _state.value = DirectWifiConnectionState(
                ssid = credentials.ssid,
                messageKey = "migration_wifi_request_failed",
                detail = it.javaClass.simpleName + ": " + it.message.orEmpty()
            )
            return
        }
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            private var directPairStarted = false

            private fun tryDirectQrPair(network: Network, linkProperties: LinkProperties? = manager.getLinkProperties(network)) {
                if (directPairStarted || credentials.targetDeviceId.isBlank() || credentials.targetPort !in 1..65535) return
                val gateway = linkProperties?.routes
                    ?.firstOrNull { it.isDefaultRoute }
                    ?.gateway
                    ?.hostAddress
                    .orEmpty()
                if (gateway.isBlank()) return
                directPairStarted = true
                ResilientMigrationController.get(context).connect(
                    MigrationPeer(
                        deviceId = credentials.targetDeviceId,
                        name = "热点手机",
                        host = gateway,
                        port = credentials.targetPort
                    ),
                    retryTransportFailures = true,
                    directPairToken = credentials.targetPairToken
                )
            }

            override fun onAvailable(network: Network) {
                if (callback !== this) return
                boundNetwork = network
                manager.bindProcessToNetwork(network)
                _state.value = DirectWifiConnectionState(
                    connected = true,
                    ssid = credentials.ssid,
                    messageKey = "migration_wifi_connected",
                    detail = "onAvailable network=$network security=${credentials.security}"
                )
                ResilientMigrationController.get(context).refreshNetworkState()
                tryDirectQrPair(network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (callback !== this) return
                tryDirectQrPair(network, linkProperties)
            }

            override fun onUnavailable() {
                if (callback !== this) return
                callback = null
                _state.value = DirectWifiConnectionState(
                    ssid = credentials.ssid,
                    messageKey = "migration_wifi_request_failed",
                    detail = "NetworkCallback.onUnavailable security=${credentials.security}",
                    canRetry = true
                )
            }

            override fun onLost(network: Network) {
                if (callback !== this) return
                if (boundNetwork == network) {
                    manager.bindProcessToNetwork(null)
                    boundNetwork = null
                    _state.value = DirectWifiConnectionState(
                        ssid = credentials.ssid,
                        messageKey = "migration_wifi_lost",
                        detail = "NetworkCallback.onLost network=$network",
                        canRetry = true
                    )
                    ResilientMigrationController.get(context).refreshNetworkState()
                }
            }
        }
        connectivityManager = manager
        callback = networkCallback
        runCatching { manager.requestNetwork(request, networkCallback, WIFI_REQUEST_TIMEOUT_MS) }
            .onFailure {
                callback = null
                _state.value = DirectWifiConnectionState(
                    ssid = credentials.ssid,
                    messageKey = "migration_wifi_request_failed",
                    detail = it.javaClass.simpleName + ": " + it.message.orEmpty(),
                    canRetry = true
                )
            }
    }

    /**
     * A Wi-Fi joined from system settings is often not Android's default network when it has
     * no internet. Bind migration traffic to it before restarting discovery.
     */
    fun bindAvailableWifi(context: Context): Boolean {
        if (callback != null || MigrationDirectHotspot.state.value.active) return boundNetwork != null
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val wifiNetworks = manager.allNetworks.filter { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val wifiNetwork = wifiNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true
        } ?: wifiNetworks.firstOrNull() ?: return false
        val capabilities = manager.getNetworkCapabilities(wifiNetwork)
        val isLocalOnly = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true
        if (boundNetwork != wifiNetwork) {
            manager.bindProcessToNetwork(wifiNetwork)
            boundNetwork = wifiNetwork
        }
        connectivityManager = manager
        _state.value = if (isLocalOnly) {
            DirectWifiConnectionState(
                connected = true,
                messageKey = "migration_wifi_manual_bound",
                detail = "Bound manually selected local-only Wi-Fi network=$wifiNetwork"
            )
        } else {
            DirectWifiConnectionState(
                detail = "Bound regular Wi-Fi network=$wifiNetwork"
            )
        }
        return true
    }

    fun bindSocket(socket: Socket) {
        boundNetwork?.bindSocket(socket)
    }

    fun bindSocket(socket: DatagramSocket) {
        boundNetwork?.bindSocket(socket)
    }

    fun retry(context: Context) {
        lastCredentials?.let { connect(context, it) }
    }

    fun cancel(context: Context) {
        val ssid = _state.value.ssid
        releaseConnection(context, clearCredentials = false)
        _state.value = DirectWifiConnectionState(
            ssid = ssid,
            messageKey = "migration_wifi_request_cancelled",
            canRetry = lastCredentials != null
        )
    }

    fun stop(context: Context? = null) {
        releaseConnection(context, clearCredentials = true)
    }

    private fun releaseConnection(context: Context?, clearCredentials: Boolean) {
        val manager = connectivityManager
            ?: context?.applicationContext?.getSystemService(ConnectivityManager::class.java)
        callback?.let { activeCallback -> runCatching { manager?.unregisterNetworkCallback(activeCallback) } }
        callback = null
        if (boundNetwork != null) runCatching { manager?.bindProcessToNetwork(null) }
        boundNetwork = null
        connectivityManager = null
        if (clearCredentials) lastCredentials = null
        _state.value = DirectWifiConnectionState()
    }

    private const val WIFI_REQUEST_TIMEOUT_MS = 15_000
}

@Suppress("DEPRECATION")
private fun hotspotCredentials(value: WifiManager.LocalOnlyHotspotReservation): WifiQrCredentials {
    return if (Build.VERSION.SDK_INT >= 30) {
        val configuration = value.softApConfiguration
        val security = when (configuration.securityType) {
            SoftApConfiguration.SECURITY_TYPE_OPEN -> "NOPASS"
            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "WPA3"
            SoftApConfiguration.SECURITY_TYPE_WPA3_OWE,
            SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION -> "OWE"
            SoftApConfiguration.SECURITY_TYPE_WPA2_PSK,
            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> "WPA"
            else -> if (configuration.passphrase.isNullOrBlank()) "NOPASS" else "WPA"
        }
        WifiQrCredentials(
            ssid = configuration.ssid.orEmpty(),
            password = configuration.passphrase.orEmpty(),
            security = security
        )
    } else {
        val configuration = value.wifiConfiguration
        val security = if (configuration?.allowedKeyManagement?.get(WifiConfiguration.KeyMgmt.NONE) == true) "NOPASS" else "WPA"
        WifiQrCredentials(
            ssid = configuration?.SSID.orEmpty().trim('"'),
            password = configuration?.preSharedKey.orEmpty().trim('"'),
            security = security
        )
    }
}

internal object MigrationLocalEndpointRegistry {
    @Volatile var servicePort: Int = 0
        private set

    fun update(port: Int) {
        if (port in 1..65535) servicePort = port
    }

    fun clear(port: Int) {
        if (servicePort == port) servicePort = 0
    }

    fun ipv4Addresses(): List<String> {
        val candidates = mutableListOf<Pair<Int, String>>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                val priority = when {
                    network.name.startsWith("wlan", true) -> 0
                    network.name.startsWith("ap", true) -> 1
                    network.name.startsWith("swlan", true) -> 2
                    else -> 10
                }
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        candidates += priority to address.hostAddress.orEmpty()
                    }
                }
            }
        }
        return candidates
            .filter { it.second.isNotBlank() }
            .sortedWith(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second })
            .map { it.second }
            .distinct()
    }
}

@Composable
private fun MigrationConnectionHelpScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ResilientMigrationController.get(context) }
    val migrationState by controller.state.collectAsState()
    val hotspot by MigrationDirectHotspot.state.collectAsState()
    val settings = remember { AppSettings.load(context) }
    val tr = remember(settings.language) { Localization.translator(context, settings.language) }
    val addresses = remember(hotspot.networkRevision) { MigrationLocalEndpointRegistry.ipv4Addresses() }
    val port = MigrationLocalEndpointRegistry.servicePort
    val preferredEndpoint = addresses.firstOrNull()?.let { address ->
        if (port > 0) "$address:$port" else address
    }.orEmpty()
    var manual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var attemptedDirectPeerId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(migrationState.incomingPairRequest, migrationState.connectedPeer) {
        if (migrationState.incomingPairRequest != null || migrationState.connectedPeer != null) {
            onClose()
        }
    }

    LaunchedEffect(hotspot.active, migrationState.peers, migrationState.pairing, attemptedDirectPeerId) {
        if (!hotspot.active) attemptedDirectPeerId = null
        directHotspotAutoConnectPeer(hotspot.active, migrationState, attemptedDirectPeerId)?.let { peer ->
            attemptedDirectPeerId = peer.deviceId
            controller.connect(peer)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(tr.text("migration_connection_help_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text(tr.text("migration_connection_help_subtitle"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onClose) { Text(tr.text("back")) }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(tr.text("migration_hotspot_title"), fontWeight = FontWeight.Black)
                    Text(tr.text("migration_hotspot_description"))
                    if (hotspot.active) {
                        Text(tr.text("migration_hotspot_ssid", hotspot.ssid), fontWeight = FontWeight.Bold)
                        Text(tr.text("migration_hotspot_password", hotspot.password.ifBlank { tr.text("migration_hotspot_no_password") }), fontWeight = FontWeight.Bold)
                        val qrBitmap = remember(hotspot.ssid, hotspot.password) {
                            createQrCodeBitmap(wifiQrPayload(hotspot.ssid, hotspot.password), 640)
                        }
                        qrBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = tr.text("migration_hotspot_qr_description"),
                                modifier = Modifier.size(210.dp).align(Alignment.CenterHorizontally)
                            )
                            Text(tr.text("migration_hotspot_qr_hint"), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(tr.text("migration_hotspot_no_internet"), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { copyHotspot(context, tr, hotspot.ssid, hotspot.password) }, modifier = Modifier.weight(1f)) { Text(tr.text("migration_copy_info")) }
                            OutlinedButton(onClick = MigrationDirectHotspot::stop, modifier = Modifier.weight(1f)) { Text(tr.text("migration_stop_hotspot")) }
                        }
                    } else {
                        Button(onClick = { MigrationDirectHotspot.start(context) }, enabled = !hotspot.starting, modifier = Modifier.fillMaxWidth()) {
                            Text(tr.text(if (hotspot.starting) "migration_hotspot_creating" else "migration_hotspot_create"))
                        }
                    }
                    val hotspotStatusKey = when {
                        migrationState.connectedPeer != null -> "migration_hotspot_paired"
                        migrationState.pairing -> "migration_hotspot_pairing"
                        migrationState.peers.isNotEmpty() -> "migration_hotspot_discovered"
                        else -> hotspot.messageKey
                    }
                    if (hotspotStatusKey.isNotBlank()) Text(tr.text(hotspotStatusKey), color = if (hotspot.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (
                        hotspot.active &&
                        !migrationState.pairing &&
                        migrationState.connectedPeer == null &&
                        migrationState.peers.isNotEmpty()
                    ) {
                        migrationState.peers.take(3).forEach { peer ->
                            Button(
                                onClick = {
                                    attemptedDirectPeerId = peer.deviceId
                                    controller.connect(peer)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(tr.text("migration_connect_device", peer.name)) }
                        }
                    }
                    if (hotspot.permanentlyDenied) {
                        OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr.text("migration_open_permission_settings"))
                        }
                    }
                    OutlinedButton(onClick = { openSettings(context, Settings.ACTION_WIFI_SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr.text("migration_open_wifi_settings"))
                    }
                }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr.text("migration_system_hotspot_title"), fontWeight = FontWeight.Black)
                    Text(tr.text("migration_system_hotspot_body"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { openSettings(context, "android.settings.TETHER_SETTINGS") },
                            modifier = Modifier.weight(1f)
                        ) { Text(tr.text("migration_hotspot_settings")) }
                        OutlinedButton(
                            onClick = { openSettings(context, Settings.ACTION_WIFI_SETTINGS) },
                            modifier = Modifier.weight(1f)
                        ) { Text(tr.text("migration_wifi_settings")) }
                    }
                }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr.text("migration_manual_title"), fontWeight = FontWeight.Black)
                    Text(tr.text("migration_manual_body"))
                    if (addresses.isEmpty() || port <= 0) {
                        Text(tr.text("migration_no_local_address"), color = MaterialTheme.colorScheme.error)
                    } else {
                        addresses.take(4).forEach { address ->
                            val endpoint = "$address:$port"
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(endpoint, fontWeight = FontWeight.Bold)
                                OutlinedButton(onClick = { copyEndpoint(context, tr, endpoint) }) { Text(tr.text("migration_copy")) }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it.trim(); error = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr.text("migration_peer_endpoint")) },
                        placeholder = { Text(preferredEndpoint.ifBlank { "192.168.1.23:47999" }) },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val parsed = parseMigrationEndpoint(manual)
                            if (parsed == null) {
                                error = tr.text("migration_invalid_endpoint")
                            } else {
                                controller.connect(
                                    MigrationPeer(
                                        deviceId = "manual-${parsed.first}-${parsed.second}",
                                        name = parsed.first,
                                        host = parsed.first,
                                        port = parsed.second
                                    )
                                )
                                onClose()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tr.text("migration_manual_connect")) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(
                        onClick = {
                            copyDiagnostics(context, tr, hotspot, addresses, port, migrationState)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tr.text("migration_copy_diagnostics")) }
                }
            }

            Card(shape = RoundedCornerShape(18.dp)) {
                Text(
                    tr.text("migration_ap_isolation_hint"),
                    Modifier.padding(15.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun parseMigrationEndpoint(text: String): Pair<String, Int>? {
    val value = text.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    val colon = value.lastIndexOf(':')
    if (colon <= 0 || colon == value.lastIndex) return null
    val host = value.substring(0, colon).trim()
    val port = value.substring(colon + 1).toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    val octets = host.split('.')
    if (octets.size != 4 || octets.any { octet -> octet.toIntOrNull()?.let { it in 0..255 } != true }) return null
    return host to port
}

private fun openSettings(context: Context, action: String) {
    val launched = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
    if (!launched) {
        runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
internal fun InlineMigrationConnectionHelp(
    migrationState: ResilientMigrationState,
    tr: Translator,
    onCreateHotspot: () -> Unit,
    onScanQr: () -> Unit
) {
    val context = LocalContext.current
    val hotspot by MigrationDirectHotspot.state.collectAsState()
    val directWifi by MigrationDirectWifiConnector.state.collectAsState()
    val directLinkActive = hotspot.active || directWifi.connected

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr.text("migration_connection_help_title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text(tr.text("migration_connection_help_subtitle"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(tr.text("migration_hotspot_title"), fontWeight = FontWeight.Black)
                Text(tr.text("migration_hotspot_description"))
                if (hotspot.active) {
                    Text(tr.text("migration_hotspot_ssid", hotspot.ssid), fontWeight = FontWeight.Bold)
                    Text(
                        tr.text("migration_hotspot_password", hotspot.password.ifBlank { tr.text("migration_hotspot_no_password") }),
                        fontWeight = FontWeight.Bold
                    )
                    val qrBitmap = remember(hotspot.ssid, hotspot.password, hotspot.security) {
                        createQrCodeBitmap(
                            wifiQrPayload(
                                hotspot.ssid,
                                hotspot.password,
                                hotspot.security,
                                migrationState.localDeviceId,
                                MigrationLocalEndpointRegistry.servicePort,
                                hotspot.pairToken
                            ),
                            640
                        )
                    }
                    qrBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = tr.text("migration_hotspot_qr_description"),
                            modifier = Modifier.size(210.dp).align(Alignment.CenterHorizontally)
                        )
                    }
                    Text(tr.text("migration_hotspot_keep_alive"), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { copyHotspot(context, tr, hotspot.ssid, hotspot.password) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tr.text("migration_copy_info")) }
                } else {
                    Button(
                        onClick = onCreateHotspot,
                        enabled = !hotspot.starting && !directWifi.requesting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(tr.text(if (hotspot.starting) "migration_hotspot_creating" else "migration_hotspot_create"))
                    }
                    if (directWifi.requesting) {
                        OutlinedButton(
                            onClick = { MigrationDirectWifiConnector.cancel(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(tr.text("migration_wifi_cancel_request")) }
                    } else {
                        OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                            Text(tr.text("migration_scan_hotspot_qr"))
                        }
                    }
                    if (directWifi.canRetry) {
                        Button(
                            onClick = { MigrationDirectWifiConnector.retry(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(tr.text("migration_wifi_retry")) }
                    }
                }

                val statusKey = when {
                    migrationState.connectedPeer != null -> "migration_hotspot_paired"
                    migrationState.pairing -> "migration_hotspot_pairing"
                    migrationState.peers.size > 1 -> "migration_hotspot_multiple_devices"
                    migrationState.peers.isNotEmpty() -> "migration_hotspot_discovered"
                    hotspot.active -> hotspot.messageKey
                    (directWifi.requesting || directWifi.connected || directWifi.ssid.isNotBlank()) && directWifi.messageKey.isNotBlank() -> directWifi.messageKey
                    else -> hotspot.messageKey
                }
                if (statusKey.isNotBlank()) {
                    Text(
                        tr.text(statusKey, directWifi.ssid),
                        color = if (directLinkActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (hotspot.permanentlyDenied) {
                    OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr.text("migration_open_permission_settings"))
                    }
                }
                OutlinedButton(
                    onClick = { openSettings(context, Settings.ACTION_WIFI_SETTINGS) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(tr.text("migration_open_wifi_settings")) }
                OutlinedButton(
                    onClick = {
                        copyDiagnostics(
                            context,
                            tr,
                            hotspot,
                            MigrationLocalEndpointRegistry.ipv4Addresses(),
                            MigrationLocalEndpointRegistry.servicePort,
                            migrationState
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(tr.text("migration_copy_diagnostics")) }
            }
        }
    }
}

@Composable
internal fun MigrationAutoPairEffect(
    migrationState: ResilientMigrationState,
    controller: ResilientMigrationController
) {
    val hotspot by MigrationDirectHotspot.state.collectAsState()
    val directWifi by MigrationDirectWifiConnector.state.collectAsState()
    var attemptedPeerId by remember { mutableStateOf<String?>(null) }
    val shouldAutoConnect = shouldAutomaticallyInitiatePair(
        hotspotActive = hotspot.active,
        directWifiConnected = directWifi.connected,
        directWifiRequesting = directWifi.requesting,
        state = migrationState
    )

    LaunchedEffect(shouldAutoConnect, migrationState.peers, migrationState.pairing, attemptedPeerId) {
        if (!shouldAutoConnect) attemptedPeerId = null
        directHotspotAutoConnectPeer(shouldAutoConnect, migrationState, attemptedPeerId)?.let { peer ->
            attemptedPeerId = peer.deviceId
            controller.connect(peer, retryTransportFailures = true)
        }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun copyEndpoint(context: Context, tr: Translator, endpoint: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("SpeedShare endpoint", endpoint))
    Toast.makeText(context, tr.text("migration_endpoint_copied", endpoint), Toast.LENGTH_SHORT).show()
}

private fun copyHotspot(context: Context, tr: Translator, ssid: String, password: String) {
    val text = tr.text("migration_hotspot_copy_text", ssid, password.ifBlank { tr.text("migration_hotspot_no_password") })
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("SpeedShare hotspot", text))
    Toast.makeText(context, tr.text("migration_hotspot_copied"), Toast.LENGTH_SHORT).show()
}

internal fun wifiQrPayload(
    ssid: String,
    password: String,
    security: String = if (password.isBlank()) "NOPASS" else "WPA",
    targetDeviceId: String = "",
    targetPort: Int = 0,
    targetPairToken: String = ""
): String {
    fun escape(value: String) = buildString {
        value.forEach { char ->
            if (char in listOf('\\', ';', ',', ':', '"')) append('\\')
            append(char)
        }
    }
    val qrSecurity = when (security.uppercase()) {
        "NOPASS" -> "nopass"
        "WPA3" -> "WPA3"
        "OWE" -> "OWE"
        else -> "WPA"
    }
    val target = if (targetDeviceId.isNotBlank() && targetPort in 1..65535 && targetPairToken.isNotBlank()) {
        "D:${escape(targetDeviceId)};R:$targetPort;K:${escape(targetPairToken)};"
    } else {
        ""
    }
    return "WIFI:T:$qrSecurity;S:${escape(ssid)};P:${escape(password)};$target;"
}

internal data class WifiQrCredentials(
    val ssid: String,
    val password: String,
    val security: String,
    val targetDeviceId: String = "",
    val targetPort: Int = 0,
    val targetPairToken: String = ""
)

internal fun parseWifiQrPayload(payload: String): WifiQrCredentials? {
    if (!payload.startsWith("WIFI:")) return null
    val fields = linkedMapOf<Char, String>()
    var index = 5
    while (index < payload.length) {
        if (payload[index] == ';') {
            index++
            continue
        }
        val key = payload[index]
        if (index + 1 >= payload.length || payload[index + 1] != ':') return null
        index += 2
        val value = StringBuilder()
        var escaped = false
        while (index < payload.length) {
            val char = payload[index++]
            when {
                escaped -> {
                    value.append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == ';' -> break
                else -> value.append(char)
            }
        }
        fields[key] = value.toString()
    }
    val ssid = fields['S']?.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 32 } ?: return null
    val security = fields['T'].orEmpty().ifBlank { "WPA" }.uppercase()
    if (security !in setOf("WPA", "WPA2", "WPA3", "NOPASS", "OWE")) return null
    val password = fields['P'].orEmpty()
    if (security !in setOf("NOPASS", "OWE") && password.length !in 8..63) return null
    val targetDeviceId = fields['D'].orEmpty()
    val targetPort = fields['R']?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 0
    val targetPairToken = fields['K'].orEmpty()
    return WifiQrCredentials(ssid, password, security, targetDeviceId, targetPort, targetPairToken)
}

internal fun directHotspotAutoConnectPeer(
    hotspotActive: Boolean,
    state: ResilientMigrationState,
    attemptedDeviceId: String?
): MigrationPeer? {
    if (!hotspotActive || state.pairing || state.incomingPairRequest != null || state.connectedPeer != null) return null
    return state.peers.singleOrNull()?.takeIf { it.deviceId != attemptedDeviceId }
}

internal fun shouldAutomaticallyInitiatePair(
    hotspotActive: Boolean,
    directWifiConnected: Boolean,
    directWifiRequesting: Boolean,
    state: ResilientMigrationState
): Boolean {
    val solePeer = state.peers.singleOrNull() ?: return false
    if (directWifiConnected && !hotspotActive) return true
    if (hotspotActive || directWifiRequesting || state.localDeviceId.isBlank()) return false
    // On a normal shared Wi-Fi, exactly one side initiates so the phones do not
    // simultaneously open competing pair requests.
    return state.localDeviceId < solePeer.deviceId
}

internal fun canClaimPairToken(
    hotspotActive: Boolean,
    expectedToken: String,
    claimedDeviceId: String,
    suppliedToken: String,
    requestingDeviceId: String
): Boolean = hotspotActive && expectedToken.isNotBlank() && requestingDeviceId.isNotBlank() &&
    suppliedToken == expectedToken && (claimedDeviceId.isBlank() || claimedDeviceId == requestingDeviceId)

private fun copyDiagnostics(
    context: Context,
    tr: Translator,
    hotspot: DirectHotspotState,
    addresses: List<String>,
    port: Int,
    migrationState: ResilientMigrationState
) {
    val text = buildString {
        appendLine("SpeedShare migration diagnostics")
        appendLine("SDK=${Build.VERSION.SDK_INT}")
        appendLine("hotspotStarting=${hotspot.starting}")
        appendLine("hotspotActive=${hotspot.active}")
        appendLine("hotspotSecurity=${hotspot.security}")
        val directWifi = MigrationDirectWifiConnector.state.value
        appendLine("directWifiRequesting=${directWifi.requesting}")
        appendLine("directWifiConnected=${directWifi.connected}")
        appendLine("directWifiSsid=${directWifi.ssid}")
        appendLine("directWifiState=${directWifi.messageKey}")
        appendLine("directWifiDetail=${directWifi.detail}")
        appendLine("addresses=${addresses.joinToString()}")
        appendLine("servicePort=$port")
        appendLine("peers=${migrationState.peers.size}")
        appendLine("connected=${migrationState.connectedPeer != null}")
        append("stage=${migrationState.stage}")
    }
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("SpeedShare diagnostics", text))
    Toast.makeText(context, tr.text("migration_diagnostics_copied"), Toast.LENGTH_SHORT).show()
}
