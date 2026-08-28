package com.alex.speedshare.migration

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import java.io.File

data class MigrationArchiveVisual(
    val label: String,
    val packageName: String,
    val versionName: String,
    val icon: Bitmap?
)

internal object MigrationAppVisualResolver {
    fun installedIcon(context: Context, packageName: String, sizePx: Int = 144): Bitmap? = runCatching {
        context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    fun archive(context: Context, packageDirectory: File, sizePx: Int = 144): MigrationArchiveVisual {
        val base = packageDirectory.listFiles()
            ?.firstOrNull { it.isFile && it.name.equals("base.apk", ignoreCase = true) }
            ?: packageDirectory.listFiles()?.firstOrNull { it.isFile && it.extension.equals("apk", true) }
        if (base == null) {
            return MigrationArchiveVisual(packageDirectory.name, packageDirectory.name, "", null)
        }
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(base.absolutePath, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(base.absolutePath, 0)
        }
        val appInfo = info?.applicationInfo
        if (appInfo != null) {
            appInfo.sourceDir = base.absolutePath
            appInfo.publicSourceDir = base.absolutePath
        }
        val label = runCatching {
            appInfo?.let(pm::getApplicationLabel)?.toString()
        }.getOrNull().orEmpty().ifBlank { info?.packageName ?: packageDirectory.name }
        val icon = runCatching {
            appInfo?.loadIcon(pm)?.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        return MigrationArchiveVisual(
            label = label,
            packageName = info?.packageName ?: packageDirectory.name,
            versionName = info?.versionName.orEmpty(),
            icon = icon
        )
    }
}
