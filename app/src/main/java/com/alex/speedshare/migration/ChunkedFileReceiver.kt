package com.alex.speedshare.migration

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

internal data class ChunkWritePlan(
    val part: File,
    val stateFile: File,
    val lock: Any,
    val chunkIndex: Int,
    val offset: Long,
    val length: Long,
    val alreadyComplete: Boolean
)

internal object ChunkedFileReceiver {
    private data class Meta(
        val migrationId: String,
        val relativePath: String,
        val size: Long,
        val modifiedAt: Long,
        val hash: String,
        val kind: String,
        val chunkSize: Long,
        val chunkCount: Int,
        val requestedTarget: File
    )

    private val locks = ConcurrentHashMap<String, Any>()
    private val hashRegex = Regex("^[0-9a-fA-F]{64}$")

    fun plan(request: JSONObject): JSONObject {
        val meta = parseMeta(request)
        if (isExactTarget(meta)) {
            return JSONObject().put("ok", true).put("action", "skip")
        }
        val part = partFile(meta)
        val stateFile = stateFile(part)
        val lock = lockFor(part)
        val completed = synchronized(lock) {
            ensureState(meta, part, stateFile)
            readCompleted(stateFile)
        }
        return JSONObject()
            .put("ok", true)
            .put("action", "send")
            .put("completed", completed.sorted().joinToString(","))
    }

    fun prepareChunk(request: JSONObject): ChunkWritePlan {
        val meta = parseMeta(request)
        val index = request.optInt("chunkIndex", -1)
        val offset = request.optLong("offset", -1L)
        val length = request.optLong("length", -1L)
        require(index in 0 until meta.chunkCount) { "invalid_chunk_index" }
        val expectedOffset = index * meta.chunkSize
        val expectedLength = min(meta.chunkSize, meta.size - expectedOffset)
        require(offset == expectedOffset && length == expectedLength && length > 0L) { "invalid_chunk_range" }

        val part = partFile(meta)
        val stateFile = stateFile(part)
        val lock = lockFor(part)
        val completed = synchronized(lock) {
            ensureState(meta, part, stateFile)
            readCompleted(stateFile)
        }
        return ChunkWritePlan(
            part = part,
            stateFile = stateFile,
            lock = lock,
            chunkIndex = index,
            offset = offset,
            length = length,
            alreadyComplete = index in completed
        )
    }

    fun writeChunk(plan: ChunkWritePlan, bytesWriter: (RandomAccessFile) -> Unit) {
        if (plan.alreadyComplete) return
        RandomAccessFile(plan.part, "rw").use { destination ->
            destination.seek(plan.offset)
            bytesWriter(destination)
            destination.fd.sync()
        }
        synchronized(plan.lock) {
            val completed = readCompleted(plan.stateFile).toMutableSet()
            completed += plan.chunkIndex
            val json = readState(plan.stateFile)
            json.put("completed", completed.sorted().joinToString(","))
            atomicWrite(plan.stateFile, json.toString())
        }
    }

    fun finalize(request: JSONObject): JSONObject {
        val meta = parseMeta(request)
        if (isExactTarget(meta)) {
            cleanupPartial(meta)
            return JSONObject().put("ok", true).put("action", "skip")
        }
        val part = partFile(meta)
        val stateFile = stateFile(part)
        val lock = lockFor(part)
        synchronized(lock) {
            ensureState(meta, part, stateFile)
            val completed = readCompleted(stateFile)
            require(completed.size == meta.chunkCount) { "chunks_incomplete" }
            require(part.length() == meta.size) { "size_mismatch" }
            if (MigrationHashCache.sha256(part) != meta.hash) {
                MigrationHashCache.invalidate(part)
                part.delete()
                stateFile.delete()
                error("hash_mismatch")
            }

            val target = if (meta.requestedTarget.exists()) conflictTarget(meta.requestedTarget) else meta.requestedTarget
            target.parentFile?.mkdirs()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = false)
                part.delete()
            }
            stateFile.delete()
            MigrationHashCache.invalidate(part)
            MigrationHashCache.invalidate(target)
            if (meta.modifiedAt > 0L) target.setLastModified(meta.modifiedAt)
            locks.remove(part.absolutePath)
            return JSONObject().put("ok", true).put("action", "complete").put("path", meta.relativePath)
        }
    }

    private fun parseMeta(request: JSONObject): Meta {
        val migrationId = request.optString("migrationId").takeIf {
            it.length in 8..80 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }
        } ?: error("invalid_migration_id")
        val relativePath = normalizeRelativePath(request.optString("path")) ?: error("invalid_path")
        val size = request.optLong("size", -1L)
        val modifiedAt = request.optLong("modifiedAt", 0L)
        val hash = request.optString("sha256")
        val kind = request.optString("kind", "file")
        val chunkSize = request.optLong("chunkSize", -1L)
        val chunkCount = request.optInt("chunkCount", -1)
        require(size > 0L && hash.matches(hashRegex)) { "invalid_file" }
        require(chunkSize in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES) { "invalid_chunk_size" }
        require(chunkCount > 0 && chunkCount.toLong() == (size + chunkSize - 1L) / chunkSize) { "invalid_chunk_count" }
        val target = resolveTarget(migrationId, relativePath, kind) ?: error("invalid_target")
        target.parentFile?.mkdirs()
        return Meta(migrationId, relativePath, size, modifiedAt, hash, kind, chunkSize, chunkCount, target)
    }

    private fun isExactTarget(meta: Meta): Boolean =
        meta.requestedTarget.isFile &&
            meta.requestedTarget.length() == meta.size &&
            runCatching { MigrationHashCache.sha256(meta.requestedTarget) }.getOrNull() == meta.hash

    private fun ensureState(meta: Meta, part: File, stateFile: File) {
        val current = runCatching { readState(stateFile) }.getOrNull()
        val valid = current != null &&
            current.optString("hash") == meta.hash &&
            current.optLong("size") == meta.size &&
            current.optLong("chunkSize") == meta.chunkSize &&
            current.optInt("chunkCount") == meta.chunkCount &&
            part.isFile && part.length() == meta.size
        if (valid) return

        MigrationHashCache.invalidate(part)
        part.delete()
        stateFile.delete()
        RandomAccessFile(part, "rw").use { it.setLength(meta.size) }
        atomicWrite(
            stateFile,
            JSONObject()
                .put("hash", meta.hash)
                .put("size", meta.size)
                .put("chunkSize", meta.chunkSize)
                .put("chunkCount", meta.chunkCount)
                .put("completed", "")
                .toString()
        )
    }

    private fun cleanupPartial(meta: Meta) {
        val part = partFile(meta)
        part.delete()
        stateFile(part).delete()
        locks.remove(part.absolutePath)
    }

    private fun readCompleted(stateFile: File): Set<Int> = readState(stateFile)
        .optString("completed")
        .split(',')
        .mapNotNull { it.toIntOrNull() }
        .toSet()

    private fun readState(stateFile: File): JSONObject = JSONObject(stateFile.readText())

    private fun atomicWrite(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun partFile(meta: Meta): File = File(
        meta.requestedTarget.parentFile,
        ".${meta.requestedTarget.name}.${meta.hash.take(12)}.speedshare.chunked.part"
    )

    private fun stateFile(part: File): File = File(part.parentFile, part.name + ".chunks.json")
    private fun lockFor(part: File): Any = locks.computeIfAbsent(part.absolutePath) { Any() }

    private fun resolveTarget(migrationId: String, relativePath: String, kind: String): File? {
        val root = android.os.Environment.getExternalStorageDirectory().canonicalFile
        val base = if (kind == "app") {
            File(root, "Download/SpeedShare/Apps/$migrationId").apply { mkdirs() }.canonicalFile
        } else {
            root
        }
        val target = File(base, relativePath).canonicalFile
        return target.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun conflictTarget(file: File): File {
        val dot = file.name.lastIndexOf('.')
        val stem = if (dot > 0) file.name.substring(0, dot) else file.name
        val ext = if (dot > 0) file.name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = File(file.parentFile, "$stem ($index)$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private const val MIN_CHUNK_BYTES = 8L * 1024L * 1024L
    private const val MAX_CHUNK_BYTES = 256L * 1024L * 1024L
}
