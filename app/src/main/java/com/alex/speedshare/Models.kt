package com.alex.speedshare

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

enum class ShareMode {
    SELECTED_FILES,
    WHOLE_STORAGE
}

data class SharedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val modifiedAt: Long
)

data class HttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>
)

data class ParsedTarget(
    val path: String,
    val query: Map<String, String>
)

enum class PreviewKind(val webValue: String) {
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    PDF("pdf"),
    DOWNLOAD("download")
}

data class WebItem(
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val size: Long,
    val modifiedAt: Long,
    val openUrl: String,
    val previewUrl: String?,
    val downloadUrl: String?,
    val thumbnailUrl: String?,
    val displayPath: String,
    val relativePath: String,
    val previewKind: PreviewKind
)

enum class TransferDirection(val webValue: String) {
    DOWNLOAD("download"),
    UPLOAD("upload")
}

data class ActiveTransferSnapshot(
    val id: Long,
    val direction: TransferDirection,
    val fileName: String,
    val clientAddress: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val bytesPerSecond: Long,
    val startedAtMs: Long
)

data class TransferSnapshot(
    val activeConnections: Int = 0,
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
    val totalDownloadedBytes: Long = 0L,
    val totalUploadedBytes: Long = 0L,
    val activeTransfers: List<ActiveTransferSnapshot> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

enum class ConflictPolicy(val webValue: String) {
    AUTO_RENAME("rename"),
    OVERWRITE("overwrite"),
    SKIP("skip");

    companion object {
        fun fromWeb(value: String?): ConflictPolicy = when (value?.lowercase()) {
            "overwrite" -> OVERWRITE
            "skip" -> SKIP
            else -> AUTO_RENAME
        }
    }
}

enum class FileOperationKind(val webValue: String) {
    COPY("copy"),
    MOVE("move"),
    TRASH("trash"),
    DELETE("delete"),
    RESTORE("restore")
}

enum class FileOperationState(val webValue: String) {
    QUEUED("queued"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled")
}

data class FileOperationSnapshot(
    val id: Long,
    val kind: FileOperationKind,
    val state: FileOperationState,
    val title: String,
    val totalBytes: Long,
    val processedBytes: Long,
    val totalItems: Int,
    val processedItems: Int,
    val bytesPerSecond: Long,
    val message: String,
    val cancellable: Boolean,
    val startedAtMs: Long,
    val updatedAtMs: Long
)

data class TrashEntry(
    val id: String,
    val name: String,
    val originalRelativePath: String,
    val deletedAtMs: Long,
    val size: Long,
    val isDirectory: Boolean
)

data class ServerUiState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val mode: ShareMode? = null,
    val selectedFileCount: Int = 0,
    val uploadEnabled: Boolean = false,
    val remoteManagementEnabled: Boolean = false,
    val keepAwakeDuringTransfer: Boolean = true,
    val wakeLockActive: Boolean = false,
    val networkAvailable: Boolean = true,
    val address: String? = null,
    val port: Int? = null,
    val autoStopMinutes: Int = 0,
    val idleStopAtMs: Long? = null,
    val statusText: String = "",
    val activeConnections: Int = 0,
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
    val totalDownloadedBytes: Long = 0L,
    val totalUploadedBytes: Long = 0L,
    val activeTransfers: List<ActiveTransferSnapshot> = emptyList()
)

object SpeedShareRuntime {
    val state = MutableStateFlow(ServerUiState())
}
