package com.alex.speedshare

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Environment.isExternalStorageManager()) {
            openActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = AppActions.REQUEST_ALL_FILES_ACCESS
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
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
            clipboardSyncEnabled = settings.clipboardSyncEnabled,
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
            state = if (current.running || current.starting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitleCompat(
                when {
                    current.starting -> tr.text("starting")
                    current.running && current.uploadEnabled -> tr.text("running_upload")
                    current.running -> tr.text("running_readonly")
                    else -> tr.text("stopped")
                }
            )
            updateTile()
        }
    }

    private fun openActivity(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                7301,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            openActivityLegacy(intent)
        }
    }

    private fun openActivityLegacy(intent: Intent) {
        val launched = runCatching {
            TileService::class.java
                .getMethod("startActivityAndCollapse", Intent::class.java)
                .invoke(this, intent)
        }.isSuccess

        if (!launched) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
    private fun Tile.subtitleCompat(value: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = value
        contentDescription = "SpeedShareWeb, $value"
    }

    companion object {
        fun requestRefresh(context: android.content.Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, SpeedShareTileService::class.java))
            }
        }
    }
}
