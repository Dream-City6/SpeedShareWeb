package com.alex.speedshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alex.speedshare.ui.theme.SpeedShareTheme

class TransferPerformanceSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme = AppSettings.load(this).themeMode
            SpeedShareTheme(themeMode = theme) {
                TransferPerformanceSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun TransferPerformanceSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = AppSettings.load(context).language
    var settings by remember { mutableStateOf(TransferPerformanceSettingsStore.load(context)) }

    fun text(zh: String, ja: String, en: String): String = when (language) {
        AppLanguage.JAPANESE -> ja
        AppLanguage.ENGLISH -> en
        else -> zh
    }

    fun save(next: TransferPerformanceSettings) {
        settings = next
        TransferPerformanceSettingsStore.save(context, next)
    }

    val resolved = settings.resolved(context)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text("传输性能", "転送パフォーマンス", "Transfer performance"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text(
                            "一个页面控制 SpeedShare 和一键换机；档位共用，但两个传输引擎会使用不同参数。",
                            "SpeedShare と端末移行を一つの画面で管理します。プリセットは共通ですが、内部パラメータは別々です。",
                            "One page controls SpeedShare and phone migration. Presets are shared, while each engine uses its own parameters."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onBack) { Text(text("返回", "戻る", "Back")) }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(text("性能档位", "パフォーマンスモード", "Performance preset"), fontWeight = FontWeight.Bold)
                    Text(
                        text(
                            "普通用户只调这里即可。自动/温控仍会根据网络和温度向下调整，不会盲目强开高并发。",
                            "通常はここだけ調整すれば十分です。ネットワークや温度に応じて自動的に下げます。",
                            "For most users this is enough. Auto tuning and thermal control may still scale down when needed."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PresetGrid(
                        selected = settings.preset,
                        label = { preset ->
                            when (preset) {
                                TransferPerformancePreset.AUTO -> text("自动", "自動", "Auto")
                                TransferPerformancePreset.POWER_SAVE -> text("省电", "省電力", "Power save")
                                TransferPerformancePreset.BALANCED -> text("均衡", "バランス", "Balanced")
                                TransferPerformancePreset.HIGH_SPEED -> text("高速", "高速", "High speed")
                                TransferPerformancePreset.EXTREME -> text("极限", "最大性能", "Extreme")
                                TransferPerformancePreset.CUSTOM -> text("自定义", "カスタム", "Custom")
                            }
                        },
                        onSelect = { save(settings.copy(preset = it)) }
                    )
                }
            }

            PerformanceSummaryCard(
                title = "SpeedShare",
                lines = listOf(
                    text("并发连接上限：${resolved.speedShare.maxClients}", "同時接続上限：${resolved.speedShare.maxClients}", "Max clients: ${resolved.speedShare.maxClients}"),
                    text("Socket 缓冲：${resolved.speedShare.socketBufferMb} MB", "Socket バッファ：${resolved.speedShare.socketBufferMb} MB", "Socket buffer: ${resolved.speedShare.socketBufferMb} MB"),
                    text("修改后在下一次启动/重启共享服务时生效", "次回サーバー起動時に反映", "Applies when the sharing server starts/restarts")
                )
            )

            PerformanceSummaryCard(
                title = text("一键换机", "端末移行", "Phone migration"),
                lines = listOf(
                    text("文件并发上限：${resolved.migration.maxFileConcurrency}", "ファイル同時処理上限：${resolved.migration.maxFileConcurrency}", "Max file concurrency: ${resolved.migration.maxFileConcurrency}"),
                    text("单个大文件分块流上限：${resolved.migration.maxChunkStreams}", "大容量ファイルの最大ストリーム：${resolved.migration.maxChunkStreams}", "Max chunk streams: ${resolved.migration.maxChunkStreams}"),
                    text("多流阈值：${resolved.migration.largeFileThresholdMb} MB", "マルチストリーム閾値：${resolved.migration.largeFileThresholdMb} MB", "Multi-stream threshold: ${resolved.migration.largeFileThresholdMb} MB"),
                    text("温控：${thermalLabel(resolved.migration.thermalPolicy, language)}", "温度制御：${thermalLabel(resolved.migration.thermalPolicy, language)}", "Thermal policy: ${thermalLabel(resolved.migration.thermalPolicy, language)}")
                )
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text("高级自定义", "詳細カスタム", "Advanced custom"), fontWeight = FontWeight.Bold)
                    Text(
                        text(
                            "调整任意一项会自动切换为“自定义”。这些数字是性能上限，不会关闭测速和温控。",
                            "いずれかを変更するとカスタムに切り替わります。数値は上限であり、自動調整や温度制御は無効になりません。",
                            "Changing any value switches to Custom. These are ceilings; auto tuning and thermal protection remain active."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text("SpeedShare", fontWeight = FontWeight.Black)
                    StepperRow(
                        title = text("并发连接上限", "同時接続上限", "Max clients"),
                        value = settings.customSpeedShareMaxClients.toString(),
                        onMinus = {
                            save(settings.copy(
                                preset = TransferPerformancePreset.CUSTOM,
                                customSpeedShareMaxClients = previousFrom(
                                    settings.customSpeedShareMaxClients,
                                    listOf(4, 8, 12, 16, 24, 32)
                                )
                            ))
                        },
                        onPlus = {
                            save(settings.copy(
                                preset = TransferPerformancePreset.CUSTOM,
                                customSpeedShareMaxClients = nextFrom(
                                    settings.customSpeedShareMaxClients,
                                    listOf(4, 8, 12, 16, 24, 32)
                                )
                            ))
                        }
                    )
                    StepperRow(
                        title = text("Socket 缓冲", "Socket バッファ", "Socket buffer"),
                        value = "${settings.customSpeedShareSocketBufferMb} MB",
                        onMinus = {
                            save(settings.copy(
                                preset = TransferPerformancePreset.CUSTOM,
                                customSpeedShareSocketBufferMb = previousFrom(
                                    settings.customSpeedShareSocketBufferMb,
                                    listOf(1, 2, 4, 8)
                                )
                            ))
                        },
                        onPlus = {
                            save(settings.copy(
                                preset = TransferPerformancePreset.CUSTOM,
                                customSpeedShareSocketBufferMb = nextFrom(
                                    settings.customSpeedShareSocketBufferMb,
                                    listOf(1, 2, 4, 8)
                                )
                            ))
                        }
                    )

                    Spacer(Modifier.height(2.dp))
                    Text(text("一键换机", "端末移行", "Phone migration"), fontWeight = FontWeight.Black)
                    StepperRow(
                        title = text("文件并发上限", "ファイル同時処理上限", "Max file concurrency"),
                        value = settings.customMigrationMaxFileConcurrency.toString(),
                        onMinus = {
                            save(settings.copy(
                                preset = TransferPerformancePreset.CUSTOM,
                                customMigrationMaxFileConcurrency = (settings.customMigrationMaxFileConcurrency - 1).coerceAtLeast(1)
                            ))
                        },
                        onPlus = {
                            save(settings.copy(
                                preset = TransferPerformancePreset.CUSTOM,
                                customMigrationMaxFileConcurrency = (settings.customMigrationMaxFileConcurrency + 1).coerceAtMost(8)
                            ))
                        }
                    )
                    StepperRow(
                        title = text("大文件分块流上限", "大容量ファイルの最大ストリーム", "Max chunk streams"),
                        value = settings.customMigrationMaxChunkStreams.toString(),
                        onMinus = {
                            save(settings.copy(
                                preset = TransferPerformancePreset.CUSTOM,
                                customMigrationMaxChunkStreams = (settings.customMigrationMaxChunkStreams - 1).coerceAtLeast(1)
                            ))
                        },
                        onPlus = {
                            save(settings.copy(
                                preset = TransferPerformancePreset.CUSTOM,
                                customMigrationMaxChunkStreams = (settings.customMigrationMaxChunkStreams + 1).coerceAtMost(8)
                            ))
                        }
                    )
                    StepperRow(
                        title = text("多流启动阈值", "マルチストリーム閾値", "Multi-stream threshold"),
                        value = "${settings.customMigrationLargeFileThresholdMb} MB",
                        onMinus = {
                            save(settings.copy(
                                preset = TransferPerformancePreset.CUSTOM,
                                customMigrationLargeFileThresholdMb = previousFrom(
                                    settings.customMigrationLargeFileThresholdMb,
                                    listOf(64, 128, 256, 512)
                                )
                            ))
                        },
                        onPlus = {
                            save(settings.copy(
                                preset = TransferPerformancePreset.CUSTOM,
                                customMigrationLargeFileThresholdMb = nextFrom(
                                    settings.customMigrationLargeFileThresholdMb,
                                    listOf(64, 128, 256, 512)
                                )
                            ))
                        }
                    )

                    Text(text("温控策略", "温度制御", "Thermal policy"), fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        MigrationThermalPolicy.entries.forEach { policy ->
                            OutlinedButton(
                                onClick = {
                                    save(settings.copy(
                                        preset = TransferPerformancePreset.CUSTOM,
                                        customMigrationThermalPolicy = policy
                                    ))
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    thermalLabel(policy, language),
                                    fontWeight = if (settings.customMigrationThermalPolicy == policy) FontWeight.Black else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text(
                    "建议：不确定时用“自动”；追求速度先用“高速”。“极限”适合近距离 5/6GHz、旗舰手机和良好散热环境。",
                    "迷った場合は「自動」、速度重視なら「高速」がおすすめです。「最大性能」は近距離の 5/6GHz と高性能端末向けです。",
                    "Recommendation: use Auto when unsure, High speed for faster transfers, and Extreme only with strong 5/6 GHz links and capable devices."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PresetGrid(
    selected: TransferPerformancePreset,
    label: (TransferPerformancePreset) -> String,
    onSelect: (TransferPerformancePreset) -> Unit
) {
    TransferPerformancePreset.entries.chunked(3).forEach { rowItems ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            rowItems.forEach { preset ->
                val active = selected == preset
                if (active) {
                    Button(
                        onClick = { onSelect(preset) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 9.dp)
                    ) { Text(label(preset), fontWeight = FontWeight.Bold) }
                } else {
                    OutlinedButton(
                        onClick = { onSelect(preset) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 9.dp)
                    ) { Text(label(preset)) }
                }
            }
            repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PerformanceSummaryCard(title: String, lines: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(title, fontWeight = FontWeight.Black)
            lines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onMinus, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("−") }
        Text(value, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onPlus, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("+") }
    }
}

private fun previousFrom(current: Int, values: List<Int>): Int {
    val index = values.indexOfFirst { it >= current }.let { if (it < 0) values.lastIndex else it }
    return values[(index - 1).coerceAtLeast(0)]
}

private fun nextFrom(current: Int, values: List<Int>): Int {
    val index = values.indexOfFirst { it > current }
    return if (index < 0) values.last() else values[index]
}

private fun thermalLabel(policy: MigrationThermalPolicy, language: AppLanguage): String = when (language) {
    AppLanguage.JAPANESE -> when (policy) {
        MigrationThermalPolicy.CONSERVATIVE -> "保守"
        MigrationThermalPolicy.BALANCED -> "バランス"
        MigrationThermalPolicy.PERFORMANCE -> "性能優先"
    }
    AppLanguage.ENGLISH -> when (policy) {
        MigrationThermalPolicy.CONSERVATIVE -> "Conservative"
        MigrationThermalPolicy.BALANCED -> "Balanced"
        MigrationThermalPolicy.PERFORMANCE -> "Performance"
    }
    else -> when (policy) {
        MigrationThermalPolicy.CONSERVATIVE -> "保守"
        MigrationThermalPolicy.BALANCED -> "均衡"
        MigrationThermalPolicy.PERFORMANCE -> "性能优先"
    }
}
