package com.alex.speedshare

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build

fun requestPinnedWholeStorageShortcut(
    context: Context,
    language: AppLanguage = AppSettings.load(context).language
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

    val manager = context.getSystemService(ShortcutManager::class.java)
    if (!manager.isRequestPinShortcutSupported) return false
    val tr = Localization.translator(context, language)

    val shortcutIntent = Intent(context, ShortcutActionActivity::class.java).apply {
        action = AppActions.SHARE_WHOLE_STORAGE
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    val shortcut = ShortcutInfo.Builder(context, "speedshare_whole_storage")
        .setShortLabel(tr.text("whole_phone"))
        .setLongLabel(tr.text("shortcut_whole_started"))
        .setIcon(Icon.createWithResource(context, R.drawable.ic_speedshare_shortcut))
        .setIntent(shortcutIntent)
        .build()

    return manager.requestPinShortcut(shortcut, null)
}

fun requestQuickSettingsTile(
    context: Context,
    language: AppLanguage = AppSettings.load(context).language,
    onResult: (String) -> Unit
) {
    val tr = Localization.translator(context, language)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        onResult(tr.text("tile_add_manual"))
        return
    }

    val manager = context.getSystemService(StatusBarManager::class.java)
    val component = ComponentName(context, SpeedShareTileService::class.java)
    val icon = Icon.createWithResource(context, R.drawable.ic_speedshare_tile)

    manager.requestAddTileService(
        component,
        "SpeedShare",
        icon,
        context.mainExecutor
    ) { result ->
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> tr.text("tile_added")
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> tr.text("tile_exists")
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> tr.text("tile_not_added")
            StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE -> tr.text("tile_package_error")
            StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS -> tr.text("tile_request_progress")
            StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT -> tr.text("tile_component_error")
            StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND -> tr.text("tile_foreground_error")
            StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE -> tr.text("tile_unsupported")
            else -> tr.text("tile_result", result)
        }
        onResult(message)
    }
}

fun createTilePreferencesIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        action = AppActions.OPEN_SETTINGS
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    return PendingIntent.getActivity(
        context,
        7201,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
