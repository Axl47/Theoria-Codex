package com.theoriacodex.app.viewer

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

internal data class UgoiraFrameSpec(
    val fileName: String,
    val delayMs: Int,
)

internal data class UgoiraArchiveStats(
    val compressedBytes: Long,
    val expandedBytes: Long,
    val frameCount: Int,
)

internal fun validateUgoiraArchive(
    archive: File,
    specs: List<UgoiraFrameSpec>,
): UgoiraArchiveStats {
    if (!archive.isFile || archive.length() <= 0L) throw IOException("Pixiv ugoira archive is empty")
    if (archive.length() > UGOIRA_MAX_COMPRESSED_BYTES) {
        throw IOException("Pixiv ugoira archive exceeds compressed-byte limit")
    }
    if (specs.isEmpty() || specs.size > UGOIRA_MAX_FRAME_COUNT) {
        throw IOException("Pixiv ugoira frame count is outside the supported range")
    }
    val expectedNames = specs.map { spec -> spec.fileName }
    if (expectedNames.any { name -> !isSafeUgoiraEntryName(name) } || expectedNames.toSet().size != specs.size) {
        throw IOException("Pixiv ugoira metadata contains unsafe or duplicate frame names")
    }

    ZipFile(archive).use { zip ->
        val entries = zip.entries().asSequence().filterNot { entry -> entry.isDirectory }.toList()
        val names = entries.map { entry -> entry.name }
        if (names.any { name -> !isSafeUgoiraEntryName(name) } || names.toSet().size != names.size) {
            throw IOException("Pixiv ugoira archive contains unsafe or duplicate entries")
        }
        if (names.toSet() != expectedNames.toSet()) {
            throw IOException("Pixiv ugoira metadata does not match archive entries")
        }

        var totalExpandedBytes = 0L
        entries.forEach { entry ->
            if (entry.size > UGOIRA_MAX_FRAME_EXPANDED_BYTES) {
                throw IOException("Pixiv ugoira frame exceeds expanded-byte limit")
            }
            var frameBytes = 0L
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(UGOIRA_ARCHIVE_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    frameBytes += read
                    totalExpandedBytes += read
                    if (frameBytes > UGOIRA_MAX_FRAME_EXPANDED_BYTES) {
                        throw IOException("Pixiv ugoira frame exceeds expanded-byte limit")
                    }
                    if (totalExpandedBytes > UGOIRA_MAX_TOTAL_EXPANDED_BYTES) {
                        throw IOException("Pixiv ugoira archive exceeds expanded-byte limit")
                    }
                }
            }
        }
        return UgoiraArchiveStats(
            compressedBytes = archive.length(),
            expandedBytes = totalExpandedBytes,
            frameCount = specs.size,
        )
    }
}

private fun isSafeUgoiraEntryName(name: String): Boolean {
    return name.isNotBlank() &&
        name.length <= UGOIRA_MAX_ENTRY_NAME_LENGTH &&
        '/' !in name &&
        '\\' !in name &&
        name != "." &&
        name != ".."
}

internal const val UGOIRA_MAX_COMPRESSED_BYTES = 96L * 1024L * 1024L
internal const val UGOIRA_MAX_TOTAL_EXPANDED_BYTES = 256L * 1024L * 1024L
internal const val UGOIRA_MAX_FRAME_EXPANDED_BYTES = 16L * 1024L * 1024L
internal const val UGOIRA_MAX_FRAME_COUNT = 400
private const val UGOIRA_MAX_ENTRY_NAME_LENGTH = 160
private const val UGOIRA_ARCHIVE_BUFFER_BYTES = 32 * 1024
