package com.alex.speedshare.migration

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alex.speedshare.AppSettings
import com.alex.speedshare.ui.theme.SpeedShareTheme
import java.net.Inet4Address
import java.net.NetworkInterface

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

class MigrationConnectionHelpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) {
                MigrationConnectionHelpScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun MigrationConnectionHelpScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ResilientMigrationController.get(context) }
    val addresses = remember { MigrationLocalEndpointRegistry.ipv4Addresses() }
    val port = MigrationLocalEndpointRegistry.servicePort
    val preferredEndpoint = addresses.firstOrNull()?.let { address ->
        if (port > 0) "$address:$port" else address
    }.orEmpty()
    var manual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

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
                    Text("连接帮助", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("正常情况下不需要这里；只有自动搜索不到设备时使用。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onClose) { Text("返回") }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("方案 1 · 热点直连", fontWeight = FontWeight.Black)
                    Text("一台手机开启热点，另一台连接这个热点，然后返回 SpeedShare。通常会自动重新发现设备。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { openSettings(context, Settings.ACTION_TETHER_SETTINGS) },
                            modifier = Modifier.weight(1f)
                        ) { Text("热点设置") }
                        OutlinedButton(
                            onClick = { openSettings(context, Settings.ACTION_WIFI_SETTINGS) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Wi‑Fi 设置") }
                    }
                }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("方案 2 · 手动 IP 连接", fontWeight = FontWeight.Black)
                    Text("在作为接收端的手机上复制下面地址，再到另一台手机输入。仍然会经过正常的“允许连接”确认。")
                    if (addresses.isEmpty() || port <= 0) {
                        Text("暂时没有可用的本机地址，请确认 Wi‑Fi/热点已经连接后返回重试。", color = MaterialTheme.colorScheme.error)
                    } else {
                        addresses.take(4).forEach { address ->
                            val endpoint = "$address:$port"
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(endpoint, fontWeight = FontWeight.Bold)
                                OutlinedButton(onClick = { copyEndpoint(context, endpoint) }) { Text("复制") }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it.trim(); error = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("对方 IP:端口") },
                        placeholder = { Text(preferredEndpoint.ifBlank { "192.168.1.23:47999" }) },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val parsed = parseEndpoint(manual)
                            if (parsed == null) {
                                error = "请输入正确的 IPv4:端口"
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
                    ) { Text("手动连接") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            Card(shape = RoundedCornerShape(18.dp)) {
                Text(
                    "如果两台手机在酒店/公司访客 Wi‑Fi 下仍无法互访，通常是路由器开启了 AP/客户端隔离。这种情况下最可靠的是让其中一台开个人热点。",
                    Modifier.padding(15.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun parseEndpoint(text: String): Pair<String, Int>? {
    val value = text.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    val colon = value.lastIndexOf(':')
    if (colon <= 0 || colon == value.lastIndex) return null
    val host = value.substring(0, colon).trim()
    val port = value.substring(colon + 1).toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    val octets = host.split('.')
    if (octets.size != 4 || octets.any { it.toIntOrNull() !in 0..255 }) return null
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

private fun copyEndpoint(context: Context, endpoint: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("SpeedShare endpoint", endpoint))
    Toast.makeText(context, "已复制 $endpoint", Toast.LENGTH_SHORT).show()
}
