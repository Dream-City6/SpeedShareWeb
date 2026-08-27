package com.alex.speedshare.migration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

internal object MigrationScannerV2 {
    fun scan(context: Context): MigrationScanResult {
        val root = Environment.getExternalStorageDirectory()
        val files = mutableListOf<MigrationFileItem>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val directory = stack.removeLast()
            val relativeDir = runCatching { directory.relativeTo(root).invariantSeparatorsPath }.getOrDefault("")
            if (shouldSkipDirectory(relativeDir)) continue
            directory.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    stack.add(child)
                } else if (child.isFile && child.canRead()) {
                    val relative = runCatching { child.relativeTo(root).invariantSeparatorsPath }.getOrNull()
                        ?: return@forEach
                    files += MigrationFileItem(
                        file = child,
                        relativePath = relative,
                        size = child.length(),
                        modifiedAt = child.lastModified(),
                        category = categoryFor(relative, child.name)
                    )
                }
            }
        }
        val apps = scanApps(context)
        MigrationAppSelectionRegistry.sync(apps)
        return MigrationScanResult(files = files, apps = apps)
    }

    fun appTransferItems(apps: List<MigrationAppItem>): List<MigrationFileItem> {
        val selected = MigrationAppSelectionRegistry.selectedPackages.value
        return apps.asSequence()
            .filter { selected.isEmpty() || it.packageName in selected }
            .flatMap { app ->
                app.apkFiles.mapIndexed { index, apk ->
                    val name = if (index == 0) "base.apk" else apk.name.ifBlank { "split-$index.apk" }
                    MigrationFileItem(
                        file = apk,
                        relativePath = "${app.packageName}/$name",
                        size = apk.length(),
                        modifiedAt = apk.lastModified(),
                        category = MigrationCategory.APPS,
                        appPackageName = app.packageName
                    )
                }.asSequence()
            }
            .toList()
    }

    private fun scanApps(context: Context): List<MigrationAppItem> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        return packages.asSequence()
            .filter { info ->
                val app = info.applicationInfo ?: return@filter false
                (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && info.packageName != context.packageName
            }
            .mapNotNull { info ->
                val app = info.applicationInfo ?: return@mapNotNull null
                val apkPaths = buildList {
                    add(app.sourceDir)
                    app.splitSourceDirs?.forEach(::add)
                }.map(::File).filter { it.isFile && it.canRead() }
                if (apkPaths.isEmpty()) return@mapNotNull null
                MigrationAppItem(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(info.packageName),
                    versionName = info.versionName.orEmpty(),
                    apkFiles = apkPaths,
                    totalBytes = apkPaths.sumOf { it.length() }
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun shouldSkipDirectory(relative: String): Boolean {
        val path = relative.trim('/').lowercase()
        if (path.isBlank()) return false
        return path == "android/data" || path.startsWith("android/data/") ||
            path == "android/obb" || path.startsWith("android/obb/") ||
            path.startsWith("download/speedshare/apps") ||
            path.contains("/.speedshare-trash") || path.startsWith(".speedshare-trash")
    }

    internal fun categoryFor(relative: String, name: String): MigrationCategory {
        val lowerPath = relative.replace('\\', '/').trim('/').lowercase()
        val top = lowerPath.substringBefore('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            top == "download" -> MigrationCategory.DOWNLOADS
            top == "documents" -> MigrationCategory.DOCUMENTS
            top == "dcim" || top == "pictures" -> MigrationCategory.PHOTOS
            top == "movies" -> MigrationCategory.VIDEOS
            top == "music" || top == "recordings" || top == "alarms" || top == "notifications" || top == "ringtones" -> MigrationCategory.MUSIC
            ext in IMAGE_EXTENSIONS -> MigrationCategory.PHOTOS
            ext in VIDEO_EXTENSIONS -> MigrationCategory.VIDEOS
            ext in AUDIO_EXTENSIONS -> MigrationCategory.MUSIC
            ext in DOCUMENT_EXTENSIONS -> MigrationCategory.DOCUMENTS
            else -> MigrationCategory.OTHER
        }
    }

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "dng", "bmp", "avif")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts", "mts")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr")
    private val DOCUMENT_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "md", "epub", "json", "xml"
    )
}
