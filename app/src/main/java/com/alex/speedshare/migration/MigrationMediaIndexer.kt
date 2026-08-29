package com.alex.speedshare.migration

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.util.concurrent.Executors

internal object MigrationMediaIndexer {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SpeedShare-MediaRefresh").apply { isDaemon = true }
    }

    fun refreshStandardMediaFolders(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            val root = Environment.getExternalStorageDirectory()
            val files = mutableListOf<String>()
            MEDIA_ROOTS.forEach { relative ->
                collectMediaFiles(File(root, relative), files)
                if (files.size >= MAX_SCAN_FILES) return@forEach
            }
            if (files.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    appContext,
                    files.take(MAX_SCAN_FILES).toTypedArray(),
                    null,
                    null
                )
            }
        }
    }

    private fun collectMediaFiles(root: File, result: MutableList<String>) {
        if (!root.isDirectory || result.size >= MAX_SCAN_FILES) return
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && result.size < MAX_SCAN_FILES) {
            val dir = stack.removeLast()
            dir.listFiles()?.forEach { child ->
                if (result.size >= MAX_SCAN_FILES) return@forEach
                when {
                    child.isDirectory -> stack.add(child)
                    child.isFile && child.extension.lowercase() in MEDIA_EXTENSIONS -> result += child.absolutePath
                }
            }
        }
    }

    private val MEDIA_ROOTS = listOf("DCIM", "Pictures", "Movies", "Music", "Recordings")
    private val MEDIA_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "dng", "bmp", "avif",
        "mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts", "mts",
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr"
    )
    private const val MAX_SCAN_FILES = 50_000
}
