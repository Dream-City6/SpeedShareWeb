package com.alex.speedshare.migration

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PendingMigrationTask(
    val migrationId: String,
    val peerDeviceId: String,
    val peerName: String,
    val createdAt: Long,
    val selectedCategories: Set<MigrationCategory>,
    val allItems: List<MigrationFileItem>,
    val completedPaths: Set<String>,
    val failedReasons: Map<String, String> = emptyMap(),
    val duplicatePolicy: MigrationDuplicatePolicy = MigrationDuplicatePolicy.SKIP_IDENTICAL_KEEP_CONFLICT
) {
    val pendingItems: List<MigrationFileItem>
        get() = allItems.filterNot { it.relativePath in completedPaths }

    val totalBytes: Long get() = allItems.sumOf { it.size }
    val totalItems: Int get() = allItems.size
    val completedBytes: Long
        get() = allItems.asSequence().filter { it.relativePath in completedPaths }.sumOf { it.size }
}

internal class MigrationTaskStore(context: Context) {
    internal val appContext: Context = context.applicationContext
    private val root = File(appContext.filesDir, "migration_tasks").apply { mkdirs() }

    @Synchronized
    fun create(
        peer: MigrationPeer,
        items: List<MigrationFileItem>,
        selectedCategories: Set<MigrationCategory>
    ): PendingMigrationTask {
        val appFiltered = MigrationAppSelectionRegistry.filterTransferItems(items)
        val mediaFiltered = MigrationMediaSelectionRegistry.filterTransferItems(appFiltered)
        val fileFiltered = MigrationFileSelectionRegistry.filterTransferItems(mediaFiltered)
        val contacts = listOfNotNull(MigrationContactsRegistry.preparedItem())
        val effectiveItems = (fileFiltered + contacts).distinctBy { it.relativePath }
        val duplicatePolicy = MigrationDuplicatePolicyRegistry.current.value
        val id = UUID.randomUUID().toString()
        val dir = taskDir(id).apply { mkdirs() }
        val metadata = JSONObject()
            .put("migrationId", id)
            .put("peerDeviceId", peer.deviceId)
            .put("peerName", peer.name)
            .put("createdAt", System.currentTimeMillis())
            .put("complete", false)
            .put("duplicatePolicy", duplicatePolicy.name)
            .put("selectedCategories", selectedCategories.joinToString(",") { it.name })
        File(dir, META_FILE).writeText(metadata.toString())
        File(dir, MANIFEST_FILE).bufferedWriter().use { writer ->
            effectiveItems.forEach { item ->
                writer.appendLine(
                    JSONObject()
                        .put("absolutePath", item.file.absolutePath)
                        .put("relativePath", item.relativePath)
                        .put("size", item.size)
                        .put("modifiedAt", item.modifiedAt)
                        .put("category", item.category.name)
                        .put("appPackageName", item.appPackageName ?: JSONObject.NULL)
                        .toString()
                )
            }
        }
        File(dir, EVENTS_FILE).writeText("")
        return PendingMigrationTask(
            migrationId = id,
            peerDeviceId = peer.deviceId,
            peerName = peer.name,
            createdAt = metadata.getLong("createdAt"),
            selectedCategories = selectedCategories,
            allItems = effectiveItems,
            completedPaths = emptySet(),
            failedReasons = emptyMap(),
            duplicatePolicy = duplicatePolicy
        )
    }

    @Synchronized
    fun markCompleted(migrationId: String, item: MigrationFileItem, skipped: Boolean) {
        appendEvent(
            migrationId,
            JSONObject()
                .put("path", item.relativePath)
                .put("status", if (skipped) "skipped" else "complete")
                .put("at", System.currentTimeMillis())
        )
    }

    @Synchronized
    fun markFailed(migrationId: String, item: MigrationFileItem, reason: String) {
        appendEvent(
            migrationId,
            JSONObject()
                .put("path", item.relativePath)
                .put("status", "failed")
                .put("reason", reason.take(300))
                .put("at", System.currentTimeMillis())
        )
    }

    @Synchronized
    fun markComplete(migrationId: String) {
        val file = File(taskDir(migrationId), META_FILE)
        if (!file.isFile) return
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        json.put("complete", true).put("completedAt", System.currentTimeMillis())
        file.writeText(json.toString())
    }

    @Synchronized
    fun discard(migrationId: String) {
        taskDir(migrationId).deleteRecursively()
    }

    @Synchronized
    fun loadLatestIncomplete(): PendingMigrationTask? {
        val task = root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull(::readTask)
            ?.filter { it.pendingItems.isNotEmpty() }
            ?.maxByOrNull { it.createdAt }
        task?.let { MigrationDuplicatePolicyRegistry.set(it.duplicatePolicy) }
        return task
    }

    private fun appendEvent(migrationId: String, event: JSONObject) {
        val dir = taskDir(migrationId)
        if (!dir.isDirectory) return
        File(dir, EVENTS_FILE).appendText(event.toString() + "\n")
    }

    private fun readTask(dir: File): PendingMigrationTask? {
        val metaFile = File(dir, META_FILE)
        val manifestFile = File(dir, MANIFEST_FILE)
        if (!metaFile.isFile || !manifestFile.isFile) return null
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return null
        if (meta.optBoolean("complete", false)) return null

        val completed = linkedSetOf<String>()
        val failures = linkedMapOf<String, String>()
        val events = File(dir, EVENTS_FILE)
        if (events.isFile) {
            events.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val path = event.optString("path").takeIf { it.isNotBlank() } ?: return@forEachLine
                when (event.optString("status")) {
                    "complete", "skipped" -> {
                        completed.add(path)
                        failures.remove(path)
                    }
                    "failed" -> if (path !in completed) {
                        failures[path] = event.optString("reason", "transfer_failed")
                    }
                }
            }
        }

        val items = mutableListOf<MigrationFileItem>()
        manifestFile.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val absolute = json.optString("absolutePath")
            val relative = normalizeRelativePath(json.optString("relativePath")) ?: return@forEachLine
            val expectedSize = json.optLong("size", -1L)
            if (absolute.isBlank() || expectedSize < 0L) return@forEachLine
            val file = File(absolute)
            val category = runCatching { MigrationCategory.valueOf(json.optString("category")) }.getOrNull()
                ?: return@forEachLine
            val item = MigrationFileItem(
                file = file,
                relativePath = relative,
                size = expectedSize,
                modifiedAt = json.optLong("modifiedAt", 0L),
                category = category,
                appPackageName = json.optString("appPackageName").takeIf { it.isNotBlank() && it != "null" }
            )
            items += item
            if (relative !in completed) {
                MigrationSourceValidator.problem(item)?.let { problem -> failures[relative] = problem }
            }
        }
        if (items.isEmpty()) return null
        val categories = meta.optString("selectedCategories")
            .split(',')
            .mapNotNull { runCatching { MigrationCategory.valueOf(it) }.getOrNull() }
            .toSet()
        val duplicatePolicy = runCatching {
            MigrationDuplicatePolicy.valueOf(meta.optString("duplicatePolicy"))
        }.getOrDefault(MigrationDuplicatePolicy.SKIP_IDENTICAL_KEEP_CONFLICT)
        return PendingMigrationTask(
            migrationId = meta.optString("migrationId", dir.name),
            peerDeviceId = meta.optString("peerDeviceId"),
            peerName = meta.optString("peerName", "SpeedShare"),
            createdAt = meta.optLong("createdAt", dir.lastModified()),
            selectedCategories = categories,
            allItems = items,
            completedPaths = completed,
            failedReasons = failures.filterKeys { it !in completed },
            duplicatePolicy = duplicatePolicy
        )
    }

    private fun taskDir(id: String): File = File(root, id)

    companion object {
        private const val META_FILE = "task.json"
        private const val MANIFEST_FILE = "manifest.ndjson"
        private const val EVENTS_FILE = "events.ndjson"
    }
}
