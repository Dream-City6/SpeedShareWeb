package com.alex.speedshare.migration

data class MigrationSession(
    val peer: MigrationPeer,
    val outboundToken: String,
    val inboundToken: String
)

data class PairSessionResult(
    val accepted: Boolean,
    val peer: MigrationPeer,
    val outboundToken: String
)

data class ReceiverStorageInfo(
    val freeBytes: Long,
    val totalBytes: Long
)

data class ResilientMigrationState(
    val stage: MigrationStage = MigrationStage.DISCOVERY,
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
    val receiverStorage: ReceiverStorageInfo? = null,
    val progress: MigrationProgress = MigrationProgress(),
    val report: MigrationReport? = null,
    val pendingTask: PendingMigrationTask? = null,
    val activeMigrationId: String? = null,
    val paused: Boolean = false,
    val reconnecting: Boolean = false,
    val status: String = "",
    val error: String? = null
)
