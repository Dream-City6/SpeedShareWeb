package com.alex.speedshare

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat

class SpeedShareTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        val settings = AppSettings.load(this)
        val tr = Localization.translator(this, settings.language)
        val current = SpeedShareRuntime.state.value

        if (current.running || current.starting) {
            SpeedShareService.stopServer(applicationContext)

            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                label = "SpeedShareWeb"
                subtitleCompat(tr.text("stopped"))
                updateTile()
            }

            return
        }

        val needsAllFilesPermission =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                    !Environment.isExternalStorageManager()

        if (needsAllFilesPermission) {
            openActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = AppActions.REQUEST_ALL_FILES_ACCESS
                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )

            return
        }

        SpeedShareService.startOrReplace(
            context = applicationContext,
            mode = ShareMode.WHOLE_STORAGE,
            files = emptyList(),
            uploadEnabled = settings.defaultUploadEnabled,
            remoteManagementEnabled = settings.remoteManagementEnabled,
            deleteToTrashByDefault = settings.deleteToTrashByDefault,
            keepAwakeDuringTransfer = settings.keepAwakeDuringTransfer,
            autoStopMinutes = settings.autoStopMinutes,
            preferredPort = settings.preferredPort,
            autoPortFallback = settings.autoPortFallback,
            copyAddressAfterStart = settings.copyAddressAfterStart,
            language = settings.language,
            successPrefix = tr.text("quick_tile_whole_started")
        )

        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "SpeedShareWeb"
            subtitleCompat(tr.text("starting"))
            updateTile()
        }
    }

    private fun updateTile() {
        val settings = AppSettings.load(this)
        val tr = Localization.translator(this, settings.language)
        val current = SpeedShareRuntime.state.value

        qsTile?.apply {
            label = "SpeedShareWeb"

            state =
                if (current.running || current.starting) {
                    Tile.STATE_ACTIVE
                } else {
                    Tile.STATE_INACTIVE
                }

            subtitleCompat(
                when {
                    current.starting ->
                        tr.text("starting")

                    current.running && current.uploadEnabled ->
                        tr.text("running_upload")

                    current.running ->
                        tr.text("running_readonly")

                    else ->
                        tr.text("stopped")
                }
            )

            updateTile()
        }
    }

    private fun openActivity(intent: Intent) {
        val wrapper = PendingIntentActivityWrapper(
            this,
            7301,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            false
        )

        TileServiceCompat.startActivityAndCollapse(
            this,
            wrapper
        )
    }

    private fun Tile.subtitleCompat(value: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            subtitle = value
        }

        contentDescription = "SpeedShareWeb, $value"
    }

    companion object {

        fun requestRefresh(context: android.content.Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(
                        context,
                        SpeedShareTileService::class.java
                    )
                )
            }
        }
    }
}