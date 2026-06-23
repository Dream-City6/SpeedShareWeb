package com.alex.speedshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Semaphore
import kotlin.math.max

class ThumbnailManager(
    private val context: Context
) {
    private val thumbnailWidth = 480
    private val thumbnailHeight = 320
    private val generationGate = Semaphore(2, true)
    private val cacheDirectory = File(context.cacheDir, "web_thumbnails").apply {
        mkdirs()
    }

    fun thumbnailForFile(file: File, mimeType: String): File? {
        if (!file.isFile || !file.canRead() || !canGenerateThumbnail(mimeType)) return null

        val key = sha256(
            "file|${file.absolutePath}|${file.length()}|${file.lastModified()}|$thumbnailWidth|$thumbnailHeight"
        )

        return getOrCreate(key) {
            createBitmapForFile(file, mimeType)
        }
    }

    fun thumbnailForSharedFile(sharedFile: SharedFile): File? {
        if (!canGenerateThumbnail(sharedFile.mimeType)) return null

        val key = sha256(
            "uri|${sharedFile.uri}|${sharedFile.name}|${sharedFile.size}|${sharedFile.modifiedAt}|$thumbnailWidth|$thumbnailHeight"
        )

        return getOrCreate(key) {
            createBitmapForUri(sharedFile)
        }
    }

    private fun getOrCreate(key: String, bitmapFactory: () -> Bitmap?): File? {
        val finalFile = File(cacheDirectory, "$key.jpg")
        if (finalFile.isFile && finalFile.length() > 0L) return finalFile

        generationGate.acquire()
        try {
            if (finalFile.isFile && finalFile.length() > 0L) return finalFile

            val bitmap = bitmapFactory() ?: return null
            val softwareBitmap = ensureSoftwareBitmap(bitmap)
            val tempFile = File(cacheDirectory, "$key.tmp")

            try {
                var compressed = false
                FileOutputStream(tempFile).use { output ->
                    compressed = softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
                    output.flush()
                }
                if (!compressed) return null

                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }

                return finalFile.takeIf { it.isFile && it.length() > 0L }
            } finally {
                if (softwareBitmap !== bitmap && !softwareBitmap.isRecycled) {
                    softwareBitmap.recycle()
                }
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
                if (tempFile.exists() && tempFile != finalFile) {
                    tempFile.delete()
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            generationGate.release()
        }
    }

    private fun createBitmapForFile(file: File, mimeType: String): Bitmap? {
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mimeType.startsWith("image/") -> {
                    ThumbnailUtils.createImageThumbnail(
                        file,
                        Size(thumbnailWidth, thumbnailHeight),
                        null
                    )
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mimeType.startsWith("video/") -> {
                    ThumbnailUtils.createVideoThumbnail(
                        file,
                        Size(thumbnailWidth, thumbnailHeight),
                        null
                    )
                }

                mimeType.startsWith("image/") -> decodeSampledBitmap(file)
                mimeType.startsWith("video/") -> createVideoFrame(file.absolutePath)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createBitmapForUri(sharedFile: SharedFile): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(
                    sharedFile.uri,
                    Size(thumbnailWidth, thumbnailHeight),
                    null
                )
            } else if (sharedFile.mimeType.startsWith("image/")) {
                decodeSampledBitmap(sharedFile)
            } else if (sharedFile.mimeType.startsWith("video/")) {
                createVideoFrame(sharedFile)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampledBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return BitmapFactory.decodeFile(file.absolutePath, options)?.let(::scaleToFit)
    }

    private fun decodeSampledBitmap(sharedFile: SharedFile): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        context.contentResolver.openInputStream(sharedFile.uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return context.contentResolver.openInputStream(sharedFile.uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)?.let(::scaleToFit)
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1

        var sampleSize = 1
        while (
            width / (sampleSize * 2) >= thumbnailWidth &&
            height / (sampleSize * 2) >= thumbnailHeight
        ) {
            sampleSize *= 2
        }
        return max(1, sampleSize)
    }

    private fun createVideoFrame(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            extractScaledVideoFrame(retriever)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun createVideoFrame(sharedFile: SharedFile): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sharedFile.uri)
            extractScaledVideoFrame(retriever)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractScaledVideoFrame(retriever: MediaMetadataRetriever): Bitmap? {
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                thumbnailWidth,
                thumbnailHeight
            )
        } else {
            retriever.getFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
        }

        return frame?.let(::scaleToFit)
    }

    private fun scaleToFit(bitmap: Bitmap): Bitmap {
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        val scale = minOf(
            thumbnailWidth.toFloat() / width.toFloat(),
            thumbnailHeight.toFloat() / height.toFloat(),
            1f
        )

        if (scale >= 1f) return bitmap

        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        ) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }
    }
}
