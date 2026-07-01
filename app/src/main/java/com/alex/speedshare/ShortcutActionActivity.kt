package com.alex.speedshare

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ShortcutActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAction(intent?.action)
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    private fun handleAction(action: String?) {
        when (action) {
            AppActions.SHARE_WHOLE_STORAGE -> {
                if (!hasManageAllFilesAccessGlobal()) {
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            this.action = AppActions.REQUEST_ALL_FILES_ACCESS
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                    )
                    return
                }

                val settings = AppSettings.load(this)
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
                    successPrefix = Localization.translator(this, settings.language).text("shortcut_whole_started")
                )
            }

            AppActions.STOP_SERVER -> {
                SpeedShareService.stopServer(applicationContext)
            }

            AppActions.CHOOSE_FILES -> {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        this.action = AppActions.CHOOSE_FILES
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                )
            }

            else -> {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            }
        }
    }
}
