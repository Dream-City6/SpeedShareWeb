package com.alex.speedshare

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import java.io.BufferedInputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Installs a normal APK or a split-APK archive using Android's PackageInstaller.Session API.
 * Supported archives are ZIP containers whose install payload consists of APK entries, including
 * SpeedShare/SAI-style .apks and many simple .xapk/.apkm archives. The platform performs final
 * package/version/signature validation and always owns the user confirmation UI.
 */
class PackageInstallActivity : Activity() {
    private var pendingUri: Uri? = null
    private var waitingForUnknownSourcesPermission = false
    private var installStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_INSTALL_RESULT) {
            handleInstallResult(intent)
            return
        }

        pendingUri = savedInstanceState?.getString(STATE_URI)?.let(Uri::parse) ?: intent.data
        if (pendingUri == null) {
            showAndFinish("No package file was provided")
            return
        }
        maybeStartInstall()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_INSTALL_RESULT) {
            handleInstallResult(intent)
        } else if (!installStarted) {
            pendingUri = intent.data
            maybeStartInstall()
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForUnknownSourcesPermission && canInstallPackages()) {
            waitingForUnknownSourcesPermission = false
            maybeStartInstall()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingUri?.let { outState.putString(STATE_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun maybeStartInstall() {
        if (installStarted) return
        if (!canInstallPackages()) {
            waitingForUnknownSourcesPermission = true
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            runCatching { startActivity(settingsIntent) }
                .onFailure { showAndFinish("Allow app installs for SpeedShareWeb in Android settings") }
            return
        }

        val uri = pendingUri ?: return
        installStarted = true
        Toast.makeText(this, "Preparing app package…", Toast.LENGTH_SHORT).show()
        thread(name = "SpeedShareWeb-PackageInstall", isDaemon = false) {
            val result = runCatching { stageAndCommit(uri) }
            runOnUiThread {
                if (result.isFailure) {
                    showAndFinish(result.exceptionOrNull()?.message ?: "Could not prepare app installation")
                } else {
                    Toast.makeText(
                        this,
                        "Package prepared. Confirm the Android installation prompt.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun stageAndCommit(uri: Uri) {
        val displayName = displayName(uri).lowercase(Locale.ROOT)
        val mimeType = contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        val knownArchive = displayName.endsWith(".apks") ||
            displayName.endsWith(".xapk") ||
            displayName.endsWith(".apkm") ||
            displayName.endsWith(".zip")
        val singleApk = !knownArchive && (displayName.endsWith(".apk") || mimeType == APK_MIME)
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
        }
        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                if (singleApk) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        session.openWrite("base.apk", 0L, querySize(uri)).use { output ->
                            input.copyTo(output, COPY_BUFFER_SIZE)
                            session.fsync(output)
                        }
                    } ?: error("Unable to open APK")
                } else {
                    stageArchive(uri, session)
                }

                val resultIntent = Intent(this, PackageInstallActivity::class.java).apply {
                    action = ACTION_INSTALL_RESULT
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val statusIntent = PendingIntent.getActivity(this, sessionId, resultIntent, flags)
                session.commit(statusIntent.intentSender)
                committed = true
            }
        } finally {
            if (!committed) runCatching { installer.abandonSession(sessionId) }
        }
    }

    private fun stageArchive(uri: Uri, session: PackageInstaller.Session) {
        val raw = contentResolver.openInputStream(uri) ?: error("Unable to open package archive")
        var apkCount = 0
        var totalBytes = 0L
        var containsObbPayload = false
        var bundletoolArchive = false
        val usedNames = linkedSetOf<String>()
        ZipInputStream(BufferedInputStream(raw, COPY_BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                if (normalizedName.equals("toc.pb", ignoreCase = true)) bundletoolArchive = true
                if (normalizedName.startsWith("Android/obb/", ignoreCase = true)) containsObbPayload = true
                if (entry.isDirectory || !normalizedName.endsWith(".apk", ignoreCase = true)) {
                    zip.closeEntry()
                    continue
                }
                apkCount++
                if (apkCount > MAX_APK_ENTRIES) error("Package archive contains too many APK entries")
                val rawName = normalizedName.substringAfterLast('/')
                    .takeIf { it.endsWith(".apk", ignoreCase = true) }
                    ?: "split-$apkCount.apk"
                val sessionName = uniqueSessionName(rawName, usedNames)
                usedNames += sessionName
                session.openWrite(sessionName, 0L, entry.size.takeIf { it >= 0L } ?: -1L).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        totalBytes += read.toLong()
                        if (totalBytes > MAX_ARCHIVE_APK_BYTES) error("Package archive is too large")
                        output.write(buffer, 0, read)
                    }
                    session.fsync(output)
                }
                zip.closeEntry()
            }
        }
        if (apkCount == 0) error("No APK files were found in this archive")
        if (bundletoolArchive) {
            error("This bundletool APK set contains device variants. Export/install a device-specific set instead.")
        }
        if (containsObbPayload) {
            error("This XAPK also contains OBB data, which Android does not let SpeedShareWeb restore reliably.")
        }
    }

    private fun handleInstallResult(intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmation != null) {
                    runCatching { startActivity(confirmation) }
                        .onFailure { showAndFinish("Android could not open the installation confirmation") }
                } else {
                    showAndFinish("Android did not provide an installation confirmation")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> showAndFinish("App installed")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Installation failed ($status)"
                showAndFinish(message)
            }
        }
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun displayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    }

    private fun querySize(uri: Uri): Long {
        val queried = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
                } else -1L
            }
        }.getOrDefault(-1L)
        return queried.takeIf { it >= 0L } ?: -1L
    }

    private fun uniqueSessionName(candidate: String, used: Set<String>): String {
        if (candidate !in used) return candidate
        val dot = candidate.lastIndexOf('.')
        val base = if (dot > 0) candidate.substring(0, dot) else candidate
        val extension = if (dot > 0) candidate.substring(dot) else ".apk"
        var index = 1
        while (true) {
            val value = "$base-$index$extension"
            if (value !in used) return value
            index++
        }
    }

    private fun showAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.alex.speedshare.action.PACKAGE_INSTALL_RESULT"
        private const val STATE_URI = "pending_uri"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val MAX_APK_ENTRIES = 256
        private const val MAX_ARCHIVE_APK_BYTES = 8L * 1024L * 1024L * 1024L
    }
}
