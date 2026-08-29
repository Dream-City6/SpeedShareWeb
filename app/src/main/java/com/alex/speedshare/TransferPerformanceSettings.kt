package com.alex.speedshare

import android.content.Context

enum class TransferPerformancePreset {
    AUTO,
    POWER_SAVE,
    BALANCED,
    HIGH_SPEED,
    EXTREME,
    CUSTOM
}

enum class MigrationThermalPolicy {
    CONSERVATIVE,
    BALANCED,
    PERFORMANCE
}

data class SpeedSharePerformanceConfig(
    val maxClients: Int,
    val socketBufferMb: Int
)

data class MigrationPerformanceConfig(
    val maxFileConcurrency: Int,
    val maxChunkStreams: Int,
    val largeFileThresholdMb: Int,
    val thermalPolicy: MigrationThermalPolicy
)

data class ResolvedTransferPerformance(
    val preset: TransferPerformancePreset,
    val speedShare: SpeedSharePerformanceConfig,
    val migration: MigrationPerformanceConfig
)

data class TransferPerformanceSettings(
    val preset: TransferPerformancePreset = TransferPerformancePreset.AUTO,
    val customSpeedShareMaxClients: Int = 24,
    val customSpeedShareSocketBufferMb: Int = 4,
    val customMigrationMaxFileConcurrency: Int = 6,
    val customMigrationMaxChunkStreams: Int = 6,
    val customMigrationLargeFileThresholdMb: Int = 128,
    val customMigrationThermalPolicy: MigrationThermalPolicy = MigrationThermalPolicy.BALANCED
) {
    fun resolved(context: Context): ResolvedTransferPerformance {
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val autoHighEnd = cpuCount >= 8
        return when (preset) {
            TransferPerformancePreset.AUTO -> ResolvedTransferPerformance(
                preset,
                speedShare = SpeedSharePerformanceConfig(
                    maxClients = if (autoHighEnd) 24 else 16,
                    socketBufferMb = if (autoHighEnd) 4 else 2
                ),
                migration = MigrationPerformanceConfig(
                    maxFileConcurrency = if (autoHighEnd) 6 else 4,
                    maxChunkStreams = if (autoHighEnd) 6 else 4,
                    largeFileThresholdMb = if (autoHighEnd) 128 else 256,
                    thermalPolicy = MigrationThermalPolicy.BALANCED
                )
            )
            TransferPerformancePreset.POWER_SAVE -> ResolvedTransferPerformance(
                preset,
                SpeedSharePerformanceConfig(maxClients = 8, socketBufferMb = 1),
                MigrationPerformanceConfig(2, 2, 512, MigrationThermalPolicy.CONSERVATIVE)
            )
            TransferPerformancePreset.BALANCED -> ResolvedTransferPerformance(
                preset,
                SpeedSharePerformanceConfig(maxClients = 16, socketBufferMb = 2),
                MigrationPerformanceConfig(4, 4, 256, MigrationThermalPolicy.BALANCED)
            )
            TransferPerformancePreset.HIGH_SPEED -> ResolvedTransferPerformance(
                preset,
                SpeedSharePerformanceConfig(maxClients = 24, socketBufferMb = 4),
                MigrationPerformanceConfig(6, 6, 128, MigrationThermalPolicy.BALANCED)
            )
            TransferPerformancePreset.EXTREME -> ResolvedTransferPerformance(
                preset,
                SpeedSharePerformanceConfig(maxClients = 32, socketBufferMb = 8),
                MigrationPerformanceConfig(8, 8, 64, MigrationThermalPolicy.PERFORMANCE)
            )
            TransferPerformancePreset.CUSTOM -> ResolvedTransferPerformance(
                preset,
                SpeedSharePerformanceConfig(
                    maxClients = customSpeedShareMaxClients.coerceIn(4, 32),
                    socketBufferMb = customSpeedShareSocketBufferMb.coerceIn(1, 8)
                ),
                MigrationPerformanceConfig(
                    maxFileConcurrency = customMigrationMaxFileConcurrency.coerceIn(1, 8),
                    maxChunkStreams = customMigrationMaxChunkStreams.coerceIn(1, 8),
                    largeFileThresholdMb = customMigrationLargeFileThresholdMb
                        .takeIf { it in setOf(64, 128, 256, 512) } ?: 128,
                    thermalPolicy = customMigrationThermalPolicy
                )
            )
        }
    }
}

object TransferPerformanceSettingsStore {
    private const val PREFS = "speedshare_transfer_performance"
    private const val KEY_PRESET = "preset"
    private const val KEY_SS_CLIENTS = "speedshare_clients"
    private const val KEY_SS_BUFFER = "speedshare_buffer_mb"
    private const val KEY_MIGRATION_FILES = "migration_file_concurrency"
    private const val KEY_MIGRATION_STREAMS = "migration_chunk_streams"
    private const val KEY_MIGRATION_THRESHOLD = "migration_threshold_mb"
    private const val KEY_MIGRATION_THERMAL = "migration_thermal_policy"

    fun load(context: Context): TransferPerformanceSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TransferPerformanceSettings(
            preset = enumValueOrDefault(
                prefs.getString(KEY_PRESET, null),
                TransferPerformancePreset.AUTO
            ),
            customSpeedShareMaxClients = prefs.getInt(KEY_SS_CLIENTS, 24).coerceIn(4, 32),
            customSpeedShareSocketBufferMb = prefs.getInt(KEY_SS_BUFFER, 4).coerceIn(1, 8),
            customMigrationMaxFileConcurrency = prefs.getInt(KEY_MIGRATION_FILES, 6).coerceIn(1, 8),
            customMigrationMaxChunkStreams = prefs.getInt(KEY_MIGRATION_STREAMS, 6).coerceIn(1, 8),
            customMigrationLargeFileThresholdMb = prefs.getInt(KEY_MIGRATION_THRESHOLD, 128)
                .takeIf { it in setOf(64, 128, 256, 512) } ?: 128,
            customMigrationThermalPolicy = enumValueOrDefault(
                prefs.getString(KEY_MIGRATION_THERMAL, null),
                MigrationThermalPolicy.BALANCED
            )
        )
    }

    fun save(context: Context, settings: TransferPerformanceSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESET, settings.preset.name)
            .putInt(KEY_SS_CLIENTS, settings.customSpeedShareMaxClients.coerceIn(4, 32))
            .putInt(KEY_SS_BUFFER, settings.customSpeedShareSocketBufferMb.coerceIn(1, 8))
            .putInt(KEY_MIGRATION_FILES, settings.customMigrationMaxFileConcurrency.coerceIn(1, 8))
            .putInt(KEY_MIGRATION_STREAMS, settings.customMigrationMaxChunkStreams.coerceIn(1, 8))
            .putInt(KEY_MIGRATION_THRESHOLD, settings.customMigrationLargeFileThresholdMb)
            .putString(KEY_MIGRATION_THERMAL, settings.customMigrationThermalPolicy.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)
}
