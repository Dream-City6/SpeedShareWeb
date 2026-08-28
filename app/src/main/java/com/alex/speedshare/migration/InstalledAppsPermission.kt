package com.alex.speedshare.migration

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

internal object InstalledAppsPermission {
    const val XIAOMI_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

    fun isRuntimeManaged(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPermissionInfo(XIAOMI_PERMISSION, 0)
        info.packageName == "com.lbe.security.miui"
    }.getOrDefault(false)

    fun isGranted(context: Context): Boolean =
        !isRuntimeManaged(context) ||
            ContextCompat.checkSelfPermission(context, XIAOMI_PERMISSION) == PackageManager.PERMISSION_GRANTED
}
