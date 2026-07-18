package com.alex.speedshare

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

internal fun commitStagedUpload(
    staging: File,
    destinationDirectory: File,
    requestedName: String,
    maxNameAttempts: Int = 10_000
): File {
    require(staging.isFile) { "Upload staging file is missing" }
    require(destinationDirectory.isDirectory) { "Upload destination directory is missing" }
    require(maxNameAttempts > 0) { "maxNameAttempts must be greater than zero" }

    val dot = requestedName.lastIndexOf('.')
    val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
    val extension = if (dot > 0) requestedName.substring(dot) else ""
    var index = 0

    while (index < maxNameAttempts) {
        val name = if (index == 0) requestedName else "$base ($index)$extension"
        val candidate = File(destinationDirectory, name)
        try {
            Files.move(staging.toPath(), candidate.toPath())
            return candidate
        } catch (_: FileAlreadyExistsException) {
            index++
        }
    }
    throw IllegalStateException("Could not allocate a unique upload name")
}
