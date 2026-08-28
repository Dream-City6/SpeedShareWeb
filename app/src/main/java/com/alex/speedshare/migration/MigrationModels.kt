package com.alex.speedshare.migration

import java.io.File

enum class MigrationRole { UNSET, OLD_PHONE, NEW_PHONE }
enum class MigrationStage { DISCOVERY, PAIRING, SPEED_TEST, ROLE, SELECTION, TRANSFERRING, VERIFYING, COMPLETE }
enum class MigrationCategory { PHOTOS, VIDEOS, MUSIC, DOCUMENTS, DOWNLOADS, OTHER, APPS }

data class MigrationPeer(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val model: String = "",
    val appVersion: String = "",
    val androidSdk: Int = 0,
    val supportedAbis: List<String> = emptyList()
)

data class IncomingPairRequest(
    val requestId: String,
    val peer: MigrationPeer
)

data class SpeedTestResult(
    val latencyMs: Long,
    val uploadBytesPerSecond: Long,
    val downloadBytesPerSecond: Long,
    val stabilityPercent: Int,
    val singleStreamBytesPerSecond: Long = 0L,
    val streamCount: Int = 1,
    val peakBytesPerSecond: Long = 0L
) {
    val averageBytesPerSecond: Long
        get() = ((uploadBytesPerSecond + downloadBytesPerSecond) / 2L).coerceAtLeast(0L)
}

data class MigrationFileItem(
    val file: File,
    val relativePath: String,
    val size: Long,
    val modifiedAt: Long,
    val category: MigrationCategory,
    val appPackageName: String? = null
)

data class MigrationAppItem(
    val packageName: String,
    val label: String,
    val versionName: String,
    val apkFiles: List<File>,
    val totalBytes: Long
)

data class MigrationScanResult(
    val files: List<MigrationFileItem> = emptyList(),
    val apps: List<MigrationAppItem> = emptyList()
) {
    fun count(category: MigrationCategory): Int = when (category) {
        MigrationCategory.APPS -> apps.size
        else -> files.count { it.category == category }
    }

    fun bytes(category: MigrationCategory): Long = when (category) {
        MigrationCategory.APPS -> apps.sumOf { it.totalBytes }
        else -> files.asSequence().filter { it.category == category }.sumOf { it.size }
    }
}

data class MigrationProgress(
    val totalBytes: Long = 0L,
    val transferredBytes: Long = 0L,
    val totalItems: Int = 0,
    val completedItems: Int = 0,
    val skippedItems: Int = 0,
    val failedItems: Int = 0,
    val bytesPerSecond: Long = 0L,
    val currentName: String = ""
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

data class MigrationReport(
    val totalBytes: Long,
    val transferredBytes: Long,
    val successCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val durationMs: Long,
    val averageBytesPerSecond: Long,
    val notMigratedCount: Int = 0
)

data class MigrationUiState(
    val stage: MigrationStage = MigrationStage.DISCOVERY,
    val discovering: Boolean = true,
    val localDeviceName: String = "",
    val peers: List<MigrationPeer> = emptyList(),
    val connectedPeer: MigrationPeer? = null,
    val incomingPairRequest: IncomingPairRequest? = null,
    val pairing: Boolean = false,
    val speedTesting: Boolean = false,
    val speedResult: SpeedTestResult? = null,
    val role: MigrationRole = MigrationRole.UNSET,
    val scanning: Boolean = false,
    val scanResult: MigrationScanResult = MigrationScanResult(),
    val selectedCategories: Set<MigrationCategory> = MigrationCategory.entries.toSet(),
    val progress: MigrationProgress = MigrationProgress(),
    val report: MigrationReport? = null,
    val status: String = "",
    val error: String? = null
)
