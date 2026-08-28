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
        val dir = temporaryMigrationDir(migrationId) ?: return false
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    fun pruneEmptyTemporaryParents(file: File, migrationId: String) {
        val stop = temporaryMigrationDir(migrationId)?.canonicalFile ?: return
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

    private fun downloadsRoot(): File = if (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) != null) {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    } else {
        File(Environment.getExternalStorageDirectory(), "Download")
    }

    private fun normalizeMigrationId(value: String): String? = value.takeIf {
        it.length in 8..80 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }
    }
}
