package com.alex.speedshare

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Discovers packages installed for the current Android user and exports their original APK files.
 * APK contents are never merged, modified or re-signed.
 */
class InstalledAppManager(private val context: Context) {
    data class InstalledApp(
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val minSdk: Int,
        val targetSdk: Int,
        val firstInstallTime: Long,
        val lastUpdateTime: Long,
        val isSystemApp: Boolean,
        val baseApk: File,
        val splitApks: List<File>
    ) {
        val apkFiles: List<File> get() = listOf(baseApk) + splitApks
        val totalBytes: Long get() = apkFiles.sumOf { it.length().coerceAtLeast(0L) }
        val isSplit: Boolean get() = splitApks.isNotEmpty()
        val exportExtension: String get() = if (isSplit) "apks" else "apk"
    }

    data class ExportedApp(
        val app: InstalledApp,
        val file: File,
        val fileName: String,
        val temporary: Boolean
    )

    private val packageManager: PackageManager get() = context.packageManager
    private val exportDirectory = File(context.cacheDir, "installed_app_exports")
    private val iconDirectory = File(context.cacheDir, "installed_app_icons")

    fun listInstalledApps(): List<InstalledApp> {
        val packages = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(0)
            }
        }.getOrDefault(emptyList())

        return packages.mapNotNull(::toInstalledApp)
            .sortedWith(
                compareBy<InstalledApp> { it.isSystemApp }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                    .thenBy { it.packageName }
            )
    }

    fun findInstalledApp(packageName: String): InstalledApp? {
        if (!PACKAGE_NAME_REGEX.matches(packageName)) return null
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull() ?: return null
        return toInstalledApp(info)
    }

    fun exportForDownload(packageName: String): ExportedApp? {
        val app = findInstalledApp(packageName) ?: return null
        if (!app.isSplit) {
            return ExportedApp(
                app = app,
                file = app.baseApk,
                fileName = "${safeFileBase(app.label, app.packageName)}.apk",
                temporary = false
            )
        }

        exportDirectory.mkdirs()
        val cacheName = buildString {
            append(safeFileBase(app.label, app.packageName))
            append('-')
            append(app.versionCode)
            append('-')
            append(app.lastUpdateTime)
            append(".apks")
        }
        val destination = File(exportDirectory, cacheName)
        if (destination.isFile && destination.length() > 0L) {
            destination.setLastModified(System.currentTimeMillis())
            return ExportedApp(app, destination, "${safeFileBase(app.label, app.packageName)}.apks", true)
        }

        val staging = File.createTempFile(".SpeedShareAppExport-", ".tmp", exportDirectory)
        try {
            writeApksArchive(app, staging)
            if (staging.length() <= 0L) error("Empty APK archive")
            if (destination.exists() && !destination.delete()) error("Could not replace cached app export")
            if (!staging.renameTo(destination)) {
                FileInputStream(staging).use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
                }
                if (!staging.delete()) staging.deleteOnExit()
            }
            pruneExportCache(destination)
            return ExportedApp(app, destination, "${safeFileBase(app.label, app.packageName)}.apks", true)
        } catch (_: Throwable) {
            runCatching { staging.delete() }
            return null
        }
    }

    fun iconFile(packageName: String): File? {
        val app = findInstalledApp(packageName) ?: return null
        iconDirectory.mkdirs()
        val cacheFile = File(
            iconDirectory,
            "${sha256Text("${app.packageName}:${app.lastUpdateTime}")}.png"
        )
        if (cacheFile.isFile && cacheFile.length() > 0L) return cacheFile

        val drawable = runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull() ?: return null
        val bitmap = Bitmap.createBitmap(APP_ICON_SIZE, APP_ICON_SIZE, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, APP_ICON_SIZE, APP_ICON_SIZE)
            drawable.draw(canvas)
            val staging = File.createTempFile(".SpeedShareIcon-", ".tmp", iconDirectory)
            FileOutputStream(staging).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) error("Icon compression failed")
                output.fd.sync()
            }
            if (cacheFile.exists()) cacheFile.delete()
            if (!staging.renameTo(cacheFile)) {
                FileInputStream(staging).use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
                }
                staging.delete()
            }
            pruneIconCache(cacheFile)
            cacheFile
        } catch (_: Throwable) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun toInstalledApp(packageInfo: PackageInfo): InstalledApp? {
        val applicationInfo = packageInfo.applicationInfo ?: return null
        val base = File(applicationInfo.sourceDir.orEmpty())
        if (!base.isFile || !base.canRead()) return null
        val splits = applicationInfo.splitSourceDirs.orEmpty()
            .map(::File)
            .filter { it.isFile && it.canRead() }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        val expectedSplitCount = applicationInfo.splitSourceDirs?.size ?: 0
        // A partial split export is worse than no export: it usually creates an un-installable archive.
        if (splits.size != expectedSplitCount) return null

        val label = runCatching { packageManager.getApplicationLabel(applicationInfo).toString() }
            .getOrDefault(packageInfo.packageName)
            .ifBlank { packageInfo.packageName }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        return InstalledApp(
            packageName = packageInfo.packageName,
            label = label,
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = versionCode,
            minSdk = applicationInfo.minSdkVersion,
            targetSdk = applicationInfo.targetSdkVersion,
            firstInstallTime = packageInfo.firstInstallTime,
            lastUpdateTime = packageInfo.lastUpdateTime,
            isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            baseApk = base,
            splitApks = splits
        )
    }

    private fun writeApksArchive(app: InstalledApp, destination: File) {
        val sourceEntries = buildList {
            add("base.apk" to app.baseApk)
            app.splitApks.forEachIndexed { index, split ->
                val original = split.name.takeIf { it.endsWith(".apk", ignoreCase = true) }
                    ?: "split_${index + 1}.apk"
                add(uniqueEntryName(original, map { it.first }.toSet()) to split)
            }
        }
        val digests = linkedMapOf<String, String>()

        FileOutputStream(destination).use { fileOutput ->
            BufferedOutputStream(fileOutput, COPY_BUFFER_SIZE).use { buffered ->
                ZipOutputStream(buffered).use { zip ->
                    // APK files are already ZIP containers. Level 0 avoids wasting CPU trying to recompress them.
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    sourceEntries.forEach { (entryName, file) ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                        BufferedInputStream(FileInputStream(file), COPY_BUFFER_SIZE).use { input ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                digest.update(buffer, 0, read)
                                zip.write(buffer, 0, read)
                            }
                        }
                        zip.closeEntry()
                        digests[entryName] = digest.digest().toHex()
                    }

                    val iconBytes = appIconPng(app.packageName)
                    if (iconBytes != null) writeZipBytes(zip, "icon.png", iconBytes)
                    writeZipText(zip, "meta.sai_v1.json", saiV1Metadata(app).toString())
                    writeZipText(zip, "meta.sai_v2.json", saiV2Metadata(app).toString())
                    writeZipText(zip, "speedshare.json", speedShareMetadata(app, sourceEntries, digests).toString())
                }
            }
            fileOutput.fd.sync()
        }
    }

    private fun saiV1Metadata(app: InstalledApp): JSONObject = JSONObject()
        .put("export_timestamp", System.currentTimeMillis())
        .put("label", app.label)
        .put("package", app.packageName)
        .put("version_code", app.versionCode)
        .put("version_name", app.versionName)

    private fun saiV2Metadata(app: InstalledApp): JSONObject {
        val component = JSONObject()
            .put("type", "apk_files")
            .put("size", app.totalBytes)
        return JSONObject()
            .put("backup_components", JSONArray().put(component))
            .put("export_timestamp", System.currentTimeMillis())
            .put("split_apk", app.isSplit)
            .put("label", app.label)
            .put("meta_version", 2)
            .put("min_sdk", app.minSdk)
            .put("package", app.packageName)
            .put("target_sdk", app.targetSdk)
            .put("version_code", app.versionCode)
            .put("version_name", app.versionName)
    }

    private fun speedShareMetadata(
        app: InstalledApp,
        sourceEntries: List<Pair<String, File>>,
        digests: Map<String, String>
    ): JSONObject {
        val entries = JSONArray()
        sourceEntries.forEachIndexed { index, (entryName, file) ->
            entries.put(
                JSONObject()
                    .put("name", entryName)
                    .put("type", if (index == 0) "base" else "split")
                    .put("size", file.length())
                    .put("sha256", digests[entryName].orEmpty())
            )
        }
        return JSONObject()
            .put("format", "speedshare-apks")
            .put("format_version", 1)
            .put("package", app.packageName)
            .put("label", app.label)
            .put("version_code", app.versionCode)
            .put("version_name", app.versionName)
            .put("min_sdk", app.minSdk)
            .put("target_sdk", app.targetSdk)
            .put("export_timestamp", System.currentTimeMillis())
            .put("source_sdk", Build.VERSION.SDK_INT)
            .put("source_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("source_density_dpi", context.resources.displayMetrics.densityDpi)
            .put("signer_sha256", JSONArray(signingCertificateDigests(app.packageName)))
            .put("apk_entries", entries)
    }

    private fun signingCertificateDigests(packageName: String): List<String> {
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
        }.getOrNull() ?: return emptyList()

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageInfo.signingInfo ?: return emptyList()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }.distinct()
    }

    private fun appIconPng(packageName: String): ByteArray? {
        val drawable = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull() ?: return null
        val bitmap = Bitmap.createBitmap(APP_ICON_SIZE, APP_ICON_SIZE, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, APP_ICON_SIZE, APP_ICON_SIZE)
            drawable.draw(canvas)
            java.io.ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeZipText(zip: ZipOutputStream, name: String, text: String) {
        writeZipBytes(zip, name, text.toByteArray(Charsets.UTF_8))
    }

    private fun writeZipBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = System.currentTimeMillis() })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun pruneExportCache(keep: File) {
        val files = exportDirectory.listFiles()?.filter { it.isFile && it != keep }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        var retainedBytes = keep.length().coerceAtLeast(0L)
        files.forEachIndexed { index, file ->
            retainedBytes += file.length().coerceAtLeast(0L)
            if (index >= MAX_CACHED_EXPORTS - 1 || retainedBytes > MAX_EXPORT_CACHE_BYTES) {
                runCatching { file.delete() }
            }
        }
    }

    private fun pruneIconCache(keep: File) {
        iconDirectory.listFiles()?.filter { it.isFile && it != keep }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED_ICONS - 1)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun uniqueEntryName(candidate: String, used: Set<String>): String {
        if (candidate !in used) return candidate
        val dot = candidate.lastIndexOf('.')
        val base = if (dot > 0) candidate.substring(0, dot) else candidate
        val extension = if (dot > 0) candidate.substring(dot) else ""
        var index = 1
        while (true) {
            val name = "$base-$index$extension"
            if (name !in used) return name
            index++
        }
    }

    companion object {
        private val PACKAGE_NAME_REGEX = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private const val APP_ICON_SIZE = 128
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val MAX_CACHED_EXPORTS = 8
        private const val MAX_CACHED_ICONS = 512
        private const val MAX_EXPORT_CACHE_BYTES = 2L * 1024L * 1024L * 1024L

        fun safeFileBase(label: String, packageName: String): String {
            val cleaned = label
                .replace(Regex("[\\r\\n/\\\\:*?\"<>|]"), "_")
                .trim()
                .trim('.')
                .take(96)
            return cleaned.ifBlank { packageName }.ifBlank { "Android-App" }
        }

        private fun sha256Text(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }
}
