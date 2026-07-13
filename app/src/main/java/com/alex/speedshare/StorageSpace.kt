package com.alex.speedshare

import java.io.File

const val UPLOAD_STORAGE_RESERVE_BYTES: Long = 256L * 1024L * 1024L

data class StorageSpaceSnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val uploadAvailableBytes: Long
)

@Suppress("UsableSpace") // Intentionally report immediately writable bytes, excluding reclaimable cache.
fun readStorageSpace(root: File, reservedByUploads: Long = 0L): StorageSpaceSnapshot {
    val total = root.totalSpace.coerceAtLeast(0L)
    val available = root.usableSpace.coerceAtLeast(0L)
    return StorageSpaceSnapshot(
        totalBytes = total,
        availableBytes = available,
        uploadAvailableBytes = calculateUploadAvailableBytes(available, reservedByUploads)
    )
}

fun calculateUploadAvailableBytes(availableBytes: Long, reservedByUploads: Long = 0L): Long =
    (availableBytes.coerceAtLeast(0L) - UPLOAD_STORAGE_RESERVE_BYTES - reservedByUploads.coerceAtLeast(0L))
        .coerceAtLeast(0L)
