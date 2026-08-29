package com.alex.speedshare.migration

import android.os.Environment
import java.io.File

/**
 * User-visible storage layout for migration-generated files.
 *
 * Download/SpeedShareWeb/
 *   Apps/<migrationId>/...
 *   Contacts/...
 *   Temporary/<migrationId>/...
 *
 * Temporary state intentionally lives in Downloads so users can inspect or delete stale
 * migration state themselves with a normal file manager.
 */
internal object MigrationStorageLayout {
    private const val ROOT_NAME = "SpeedShareWeb"
    private const val APPS_NAME = "Apps"
    private const val CONTACTS_NAME = "Contacts"
    private const val TEMP_NAME = "Temporary"

    fun root(): File = File(downloadsRoot(), ROOT_NAME).apply { mkdirs() }

    fun appsRoot(): File = File(root(), APPS_NAME).apply { mkdirs() }

    fun appsMigrationDir(migrationId: String): File? {
        val id = normalizeMigrationId(migrationId) ?: return null
        return File(appsRoot(), id).apply { mkdirs() }
    }

    fun contactsDir(): File = File(root(), CONTACTS_NAME).apply { mkdirs() }

    fun temporaryRoot(): File = File(root(), TEMP_NAME).apply { mkdirs() }

    fun temporaryMigrationDir(migrationId: String): File? {
        val id = normalizeMigrationId(migrationId) ?: return null
        return File(temporaryRoot(), id).apply { mkdirs() }
    }

    fun singlePartFile(migrationId: String, relativePath: String, hash: String): File? =
        temporaryFile(migrationId, relativePath, ".${hash.take(12)}.part")

    fun chunkPartFile(migrationId: String, relativePath: String, hash: String): File? =
        temporaryFile(migrationId, relativePath, ".${hash.take(12)}.chunked.part")

    fun chunkStateFile(part: File): File = File(part.parentFile, part.name + ".chunks.json")

    fun cleanupTemporary(migrationId: String): Boolean {
        val id = normalizeMigrationId(migrationId) ?: return false
        val dir = File(temporaryRoot(), id)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    fun cleanupAllTemporary(): Boolean {
        val root = temporaryRoot()
        if (!root.exists()) return true
        val removed = root.deleteRecursively()
        if (removed) root.mkdirs()
        return removed
    }

    fun temporaryBytes(): Long = directoryBytes(temporaryRoot())

    fun pruneEmptyTemporaryParents(file: File, migrationId: String) {
        val id = normalizeMigrationId(migrationId) ?: return
        val stop = File(temporaryRoot(), id).canonicalFile
        var current = file.parentFile
        while (current != null) {
            val canonical = runCatching { current.canonicalFile }.getOrNull() ?: return
            if (canonical == stop || !canonical.path.startsWith(stop.path + File.separator)) break
            if (canonical.listFiles()?.isNotEmpty() == true) break
            if (!canonical.delete()) break
            current = canonical.parentFile
        }
        if (stop.listFiles()?.isEmpty() == true) stop.delete()
    }

    private fun temporaryFile(
        migrationId: String,
        relativePath: String,
        suffix: String
    ): File? {
        val base = temporaryMigrationDir(migrationId)?.canonicalFile ?: return null
        val normalized = normalizeRelativePath(relativePath) ?: return null
        val source = File(normalized)
        val relativeParent = source.parent.orEmpty()
        val fileName = source.name + suffix
        val parent = if (relativeParent.isBlank()) base else File(base, relativeParent)
        val target = File(parent, fileName).canonicalFile
        if (!target.path.startsWith(base.path + File.separator)) return null
        target.parentFile?.mkdirs()
        return target
    }

    private fun directoryBytes(root: File): Long {
        if (!root.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            current.listFiles()?.forEach { child ->
                if (child.isDirectory) stack.add(child) else if (child.isFile) total += child.length().coerceAtLeast(0L)
            }
        }
        return total
    }

    @Suppress("DEPRECATION")
    private fun downloadsRoot(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: File(Environment.getExternalStorageDirectory(), "Download")

    private fun normalizeMigrationId(value: String): String? = value.takeIf {
        it.length in 8..80 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }
    }
}
