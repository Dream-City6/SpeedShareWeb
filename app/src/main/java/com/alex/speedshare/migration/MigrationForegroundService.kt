package com.alex.speedshare.migration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class MigrationForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SpeedShare migration", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Phone migration progress"
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0)?.coerceIn(0, 100) ?: 0
        val detail = intent?.getStringExtra(EXTRA_DETAIL).orEmpty()
        val notification = buildNotification(progress, detail)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(progress: Int, detail: String): Notification {
        val openIntent = Intent(this, MigrationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.alex.speedshare.R.drawable.ic_speedshare_tile)
            .setContentTitle("SpeedShare 一键换机")
            .setContentText(detail.ifBlank { "正在迁移数据" })
            .setProgress(100, progress, progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "speedshare_migration"
        private const val NOTIFICATION_ID = 2091
        private const val ACTION_UPDATE = "com.alex.speedshare.migration.UPDATE"
        private const val EXTRA_PROGRESS = "progress"
        private const val EXTRA_DETAIL = "detail"

        fun update(context: Context, progress: MigrationProgress, detail: String) {
            val percent = (progress.fraction * 100f).toInt().coerceIn(0, 100)
            val intent = Intent(context, MigrationForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_PROGRESS, percent)
                putExtra(EXTRA_DETAIL, detail)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MigrationForegroundService::class.java))
        }
    }
}
