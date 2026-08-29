package com.alex.speedshare.migration

import android.content.Context
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object MigrationDiagnosticsExporter {
    fun export(context: Context): Result<File> = runCatching {
        val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) appInfo.longVersionCode else appInfo.versionCode.toLong()
        val health = MigrationDeviceHealthReader.read(context)
        val permissions = MigrationPermissionRequirements.snapshot(context)
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SpeedShareWeb/Diagnostics"
        ).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val out = File(root, "migration-diagnostics-$stamp.txt")

        val tasksRoot = File(context.filesDir, "migration_tasks")
        val taskDirs = tasksRoot.listFiles().orEmpty().filter { it.isDirectory }.sortedByDescending { it.lastModified() }
        val failureCounts = linkedMapOf<String, Int>()
        var completedTasks = 0
        var incompleteTasks = 0
        var manifestItems = 0L
        var manifestBytes = 0L

        taskDirs.take(20).forEach { dir ->
            val meta = File(dir, "task.json").takeIf { it.isFile }
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            if (meta?.optBoolean("complete", false) == true) completedTasks++ else incompleteTasks++

            File(dir, "manifest.ndjson").takeIf { it.isFile }?.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching { JSONObject(line) }.getOrNull()?.let { item ->
                    manifestItems++
                    manifestBytes += item.optLong("size", 0L).coerceAtLeast(0L)
                }
            }
            File(dir, "events.ndjson").takeIf { it.isFile }?.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (event.optString("status") != "failed") return@forEachLine
                val normalized = normalizeReason(event.optString("reason", "transfer_failed"))
                failureCounts[normalized] = (failureCounts[normalized] ?: 0) + 1
            }
        }

        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("SpeedShareWeb Migration Diagnostics")
            writer.appendLine("generatedAt=${System.currentTimeMillis()}")
            writer.appendLine("package=${context.packageName}")
            writer.appendLine("versionName=${appInfo.versionName.orEmpty()}")
            writer.appendLine("versionCode=$versionCode")
            writer.appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            writer.appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            writer.appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            writer.appendLine()
            writer.appendLine("[health]")
            writer.appendLine("battery=${health.batteryPercent}")
            writer.appendLine("charging=${health.charging}")
            writer.appendLine("batteryTemperatureC=${health.batteryTemperatureC ?: -1f}")
            writer.appendLine("thermalStatus=${health.thermalStatus}")
            writer.appendLine()
            writer.appendLine("[permissions]")
            writer.appendLine("storage=${permissions.storage}")
            writer.appendLine("media=${permissions.media}")
            writer.appendLine("apps=${permissions.apps}")
            writer.appendLine("contacts=${permissions.contacts}")
            writer.appendLine("notifications=${permissions.notifications}")
            writer.appendLine("partialVisualMedia=${permissions.partialVisualMedia}")
            writer.appendLine()
            writer.appendLine("[workspace]")
            writer.appendLine("temporaryBytes=${MigrationStorageLayout.temporaryBytes()}")
            writer.appendLine("appsBytes=${directoryBytes(MigrationStorageLayout.appsRoot())}")
            writer.appendLine("contactsBytes=${directoryBytes(MigrationStorageLayout.contactsDir())}")
            writer.appendLine()
            writer.appendLine("[tasks]")
            writer.appendLine("recentTaskDirectories=${taskDirs.take(20).size}")
            writer.appendLine("completedTasks=$completedTasks")
            writer.appendLine("incompleteTasks=$incompleteTasks")
            writer.appendLine("manifestItems=$manifestItems")
            writer.appendLine("manifestBytes=$manifestBytes")
            writer.appendLine()
            writer.appendLine("[failureSummary]")
            if (failureCounts.isEmpty()) {
                writer.appendLine("none")
            } else {
                failureCounts.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
                    writer.appendLine("$reason=$count")
                }
            }
            writer.appendLine()
            writer.appendLine("privacy=file names, file contents, contact values and peer device names are intentionally omitted")
        }
        out
    }

    private fun directoryBytes(root: File): Long =
        if (!root.exists()) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun normalizeReason(raw: String): String {
        val text = raw.lowercase(Locale.ROOT)
        return when {
            "空间" in raw || "insufficient_space" in text -> "insufficient_space"
            "权限" in raw || "permission" in text -> "permission"
            "完整性" in raw || "hash" in text -> "hash_mismatch"
            "会话" in raw || "session" in text -> "session_expired"
            "超时" in raw || "timeout" in text -> "timeout"
            "连接" in raw || "broken pipe" in text || "reset" in text -> "network_interruption"
            "源文件" in raw -> "source_changed_or_missing"
            else -> raw.take(80).replace('=', '_').replace('\n', ' ')
        }
    }
}
