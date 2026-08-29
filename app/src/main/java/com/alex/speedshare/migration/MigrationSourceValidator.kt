package com.alex.speedshare.migration

import kotlin.math.abs

/**
 * Protects a persisted migration task from silently changing meaning after a pause/restart.
 * A task keeps its original path/size/mtime snapshot; if the source later disappears or changes,
 * the item remains in the task and is reported as a source-side failure instead of vanishing.
 */
internal object MigrationSourceValidator {
    fun problem(item: MigrationFileItem): String? {
        val file = item.file
        return when {
            !file.exists() || !file.isFile -> "旧手机源文件已删除"
            !file.canRead() -> "旧手机源文件无法读取"
            file.length() != item.size -> "旧手机源文件大小已变化"
            item.modifiedAt > 0L && file.lastModified() > 0L &&
                abs(file.lastModified() - item.modifiedAt) > MTIME_TOLERANCE_MS -> "旧手机源文件已修改"
            else -> null
        }
    }

    private const val MTIME_TOLERANCE_MS = 2_000L
}
