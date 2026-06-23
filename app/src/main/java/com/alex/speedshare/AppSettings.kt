package com.alex.speedshare

import android.content.Context

data class SpeedShareSettings(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val defaultMode: ShareMode = ShareMode.WHOLE_STORAGE,
    val defaultUploadEnabled: Boolean = false,
    val remoteManagementEnabled: Boolean = false,
    val deleteToTrashByDefault: Boolean = true,
    val autoStartIncomingShare: Boolean = true,
    val keepAwakeDuringTransfer: Boolean = true,
    val autoStopMinutes: Int = 0,
    val preferredPort: Int = 9999,
    val autoPortFallback: Boolean = true,
    val copyAddressAfterStart: Boolean = true
)

object AppSettings {
    private const val PREFS_NAME = "speedshare_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_DEFAULT_MODE = "default_mode"
    private const val KEY_DEFAULT_UPLOAD = "default_upload"
    private const val KEY_REMOTE_MANAGEMENT = "remote_management"
    private const val KEY_DELETE_TO_TRASH = "delete_to_trash"
    private const val KEY_AUTO_START_SHARE = "auto_start_share"
    private const val KEY_KEEP_AWAKE = "keep_awake"
    private const val KEY_AUTO_STOP_MINUTES = "auto_stop_minutes"
    private const val KEY_PREFERRED_PORT = "preferred_port"
    private const val KEY_AUTO_PORT_FALLBACK = "auto_port_fallback"
    private const val KEY_COPY_ADDRESS_AFTER_START = "copy_address_after_start"

    fun load(context: Context): SpeedShareSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = runCatching {
            ShareMode.valueOf(
                prefs.getString(KEY_DEFAULT_MODE, ShareMode.WHOLE_STORAGE.name)
                    ?: ShareMode.WHOLE_STORAGE.name
            )
        }.getOrDefault(ShareMode.WHOLE_STORAGE)

        return SpeedShareSettings(
            language = AppLanguage.fromStored(prefs.getString(KEY_LANGUAGE, AppLanguage.SYSTEM.storedValue)),
            defaultMode = mode,
            defaultUploadEnabled = prefs.getBoolean(KEY_DEFAULT_UPLOAD, false),
            remoteManagementEnabled = prefs.getBoolean(KEY_REMOTE_MANAGEMENT, false),
            deleteToTrashByDefault = prefs.getBoolean(KEY_DELETE_TO_TRASH, true),
            autoStartIncomingShare = prefs.getBoolean(KEY_AUTO_START_SHARE, true),
            keepAwakeDuringTransfer = prefs.getBoolean(KEY_KEEP_AWAKE, true),
            autoStopMinutes = prefs.getInt(KEY_AUTO_STOP_MINUTES, 0)
                .takeIf { it in setOf(0, 10, 30, 60) } ?: 0,
            preferredPort = prefs.getInt(KEY_PREFERRED_PORT, 9999)
                .coerceIn(1024, 65535),
            autoPortFallback = prefs.getBoolean(KEY_AUTO_PORT_FALLBACK, true),
            copyAddressAfterStart = prefs.getBoolean(KEY_COPY_ADDRESS_AFTER_START, true)
        )
    }

    fun save(context: Context, settings: SpeedShareSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, settings.language.storedValue)
            .putString(KEY_DEFAULT_MODE, settings.defaultMode.name)
            .putBoolean(KEY_DEFAULT_UPLOAD, settings.defaultUploadEnabled)
            .putBoolean(KEY_REMOTE_MANAGEMENT, settings.remoteManagementEnabled)
            .putBoolean(KEY_DELETE_TO_TRASH, settings.deleteToTrashByDefault)
            .putBoolean(KEY_AUTO_START_SHARE, settings.autoStartIncomingShare)
            .putBoolean(KEY_KEEP_AWAKE, settings.keepAwakeDuringTransfer)
            .putInt(KEY_AUTO_STOP_MINUTES, settings.autoStopMinutes)
            .putInt(KEY_PREFERRED_PORT, settings.preferredPort.coerceIn(1024, 65535))
            .putBoolean(KEY_AUTO_PORT_FALLBACK, settings.autoPortFallback)
            .putBoolean(KEY_COPY_ADDRESS_AFTER_START, settings.copyAddressAfterStart)
            .apply()
    }
}
