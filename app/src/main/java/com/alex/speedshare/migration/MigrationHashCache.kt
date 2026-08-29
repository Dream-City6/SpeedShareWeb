package com.alex.speedshare.migration

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Avoids hashing the same large file again during automatic retry/reconnect and limits concurrent
 * disk-heavy SHA-256 work so six network lanes do not turn into six simultaneous full-file reads.
 */
internal object MigrationHashCache {
    private data class Key(val path: String, val size: Long, val modifiedAt: Long)

    private val cache = ConcurrentHashMap<Key, String>()
    private val hashSlots = Semaphore(2, true)

    fun sha256(file: File): String {
        val key = Key(file.absolutePath, file.length(), file.lastModified())
        cache[key]?.let { return it }
        hashSlots.acquire()
        try {
            cache[key]?.let { return it }
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }.also { cache[key] = it }
        } finally {
            hashSlots.release()
        }
    }

    fun invalidate(file: File) {
        cache.keys.removeIf { it.path == file.absolutePath }
    }

    fun clear() = cache.clear()
}
