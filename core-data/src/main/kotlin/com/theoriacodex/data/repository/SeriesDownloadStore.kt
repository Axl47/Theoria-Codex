package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.mediaTypeOfExtension
import com.theoriacodex.data.storage.sha256
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.series.SeriesId
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Downloaded chapters on disk, and the only record of which chapters are downloaded: a chapter is on the device exactly
 * when its folder is, and a folder is only ever there complete. Pages are written into a staging folder, the chapter's
 * address last, and the folder is then renamed into place in one step. A download stopped at any point leaves at most
 * a staging folder, which nothing reads and the next download of that chapter or [removeUnfinished] removes.
 *
 * Neither the database nor a backup says anything about downloads, so they cannot disagree with the files: a database
 * that returns without them (Android's backup, a device transfer) shows nothing downloaded, and a profile-backup restore
 * shows the chapters whose files are here.
 *
 * Layout: `root/<series>/<chapter>/0001.jpg …` and an `address` file naming the chapter. Folder names are digests,
 * since addresses can be any text.
 */
class SeriesDownloadStore(private val root: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** The addresses of [id]'s chapters on the device. */
    suspend fun downloaded(id: SeriesId): Set<String> = withContext(io) {
        seriesDirectory(id).listFiles().orEmpty().mapNotNullTo(HashSet()) { it.completedAddress() }
    }

    /** The pages of the chapter at [entry] in reading order, as files; null when it is not on the device. */
    suspend fun pages(id: SeriesId, entry: String): List<ImageRef>? = withContext(io) {
        val folder = chapterDirectory(id, entry)
        if (folder.completedAddress() != entry) return@withContext null
        folder.listFiles().orEmpty().filter { it.name != ADDRESS_FILE }
            .sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
            .map { ImageRef(url = null, localPath = it.absolutePath, mime = mediaTypeOfExtension(it.extension)) }
            .ifEmpty { null }
    }

    /**
     * Downloads the chapter at [entry]: [fill] writes its pages into the folder it is handed, named [pageFileName], and
     * the chapter appears on the device only once [fill] returns. A failed or cancelled [fill] leaves nothing behind,
     * and a chapter already on the device stays as it was until the new copy replaces it.
     */
    suspend fun write(id: SeriesId, entry: String, fill: suspend (staging: File) -> Unit) {
        val folder = chapterDirectory(id, entry)
        val staging = File(folder.parentFile, STAGING_PREFIX + folder.name)
        withContext(io) {
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw IOException("Could not create a folder for the chapter")
        }
        try {
            fill(staging)
            // Once the pages are in, the chapter is committed whole, even by a caller cancelled meanwhile.
            withContext(io + NonCancellable) {
                File(staging, ADDRESS_FILE).writeText(entry)
                folder.deleteRecursively()
                if (!staging.renameTo(folder)) throw IOException("Could not finish saving the chapter")
            }
        } finally {
            withContext(io + NonCancellable) { staging.deleteRecursively() }
        }
    }

    suspend fun delete(id: SeriesId, entry: String) {
        withContext(io) { chapterDirectory(id, entry).deleteRecursively() }
    }

    /** Deletes everything downloaded for [id], chapters its source no longer lists included. */
    suspend fun deleteSeries(id: SeriesId) {
        withContext(io) { seriesDirectory(id).deleteRecursively() }
    }

    suspend fun deleteAll() {
        withContext(io) { root.deleteRecursively() }
    }

    /** The bytes every download takes, staging folders included. */
    suspend fun sizeBytes(): Long = withContext(io) { root.walk().filter(File::isFile).sumOf(File::length) }

    /** Deletes what downloads stopped midway left, such as by the process ending. Call while none is running. */
    suspend fun removeUnfinished() {
        withContext(io) {
            root.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().asList() }
                .filter { it.name.startsWith(STAGING_PREFIX) }
                .forEach(File::deleteRecursively)
        }
    }

    private fun seriesDirectory(id: SeriesId) = File(root, "${id.source.id}\n${id.address}".toByteArray().sha256())

    private fun chapterDirectory(id: SeriesId, entry: String) = File(seriesDirectory(id), entry.toByteArray().sha256())

    /** The chapter this folder completes; null for a staging folder or one that is not a chapter's. */
    private fun File.completedAddress(): String? {
        if (name.startsWith(STAGING_PREFIX)) return null
        val address = File(this, ADDRESS_FILE)
        return if (address.isFile) address.readText() else null
    }

    companion object {
        /** Page [index] (from 0) as a file with [extension]; numbered from 1 so the folder reads in order. */
        fun pageFileName(index: Int, extension: String): String = "${(index + 1).toString().padStart(PAGE_DIGITS, '0')}.$extension"

        private const val PAGE_DIGITS = 4
        private const val ADDRESS_FILE = "address"
        private const val STAGING_PREFIX = ".partial-"
    }
}
