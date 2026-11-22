package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.mediaTypeOfExtension
import com.theoriacodex.data.storage.sha256
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesId
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Downloaded chapters on disk, and the only record of which chapters are downloaded: a chapter is on the device exactly
 * when its folder holds a marker naming it and every page file the marker lists, at the size it lists.
 *
 * A download writes its pages into a staging folder of its own, then the marker, and renames the folder into place in
 * one step. A delete first renames the folder out of place in one step, then removes it. A download or delete stopped
 * at any point therefore leaves at most a staging or deleted folder, which nothing reads and [removeUnfinished]
 * removes.
 *
 * Which chapters are on the device is read from the files once, on first use, and then kept in memory as this store
 * publishes and deletes them; nothing else is stored about them. Neither the database nor a backup says anything about
 * downloads, so they cannot disagree with the files: a database that returns without them (Android's backup, a device
 * transfer) shows nothing downloaded, and a profile-backup restore shows the chapters whose files are here.
 *
 * Layout: `root/<series>/<chapter>/0001.jpg …` and a `chapter.json` marker. Folder names are digests, since addresses
 * can be any text.
 */
class SeriesDownloadStore(private val root: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** Publishing a chapter, deleting and reading the files into [index] take turns. */
    private val lock = Mutex()

    /** The chapters on the device by series; null until read from the files. Changed only while holding [lock]. */
    private val index = MutableStateFlow<Map<SeriesId, Set<String>>?>(null)

    /** Every series with chapters on the device, with their addresses. */
    suspend fun downloaded(): Map<SeriesId, Set<String>> = index.value ?: lock.withLock { loadLocked() }

    /** The addresses of [id]'s chapters on the device. */
    suspend fun downloaded(id: SeriesId): Set<String> = downloaded()[id].orEmpty()

    /** [downloaded] for [id], again whenever it changes. */
    fun observe(id: SeriesId): Flow<Set<String>> = flow {
        downloaded()
        emitAll(index.filterNotNull().map { it[id].orEmpty() }.distinctUntilChanged())
    }

    /**
     * The pages of the chapter at [entry] in reading order, as files; null when it is not on the device, which is also
     * what a chapter deleted while this reads it shows.
     */
    suspend fun pages(id: SeriesId, entry: String): List<ImageRef>? = withContext(io) {
        val folder = chapterDirectory(id, entry)
        val marker = folder.marker()?.takeIf { it.address == entry } ?: return@withContext null
        marker.pages.map { page ->
            val file = File(folder, page.name)
            ImageRef(url = null, localPath = file.absolutePath, mime = mediaTypeOfExtension(file.extension))
        }
    }

    /**
     * Downloads the chapter at [entry]: [fill] writes its pages into the folder it is handed and returns them in reading
     * order, and the chapter appears on the device only once [fill] returns. A failed or cancelled download leaves
     * nothing behind, a cancelled one never appears even with every page in, and a chapter already on the device stays
     * as it was until the new copy replaces it.
     */
    suspend fun write(id: SeriesId, entry: String, fill: suspend (staging: File) -> List<File>) {
        val folder = chapterDirectory(id, entry)
        // Each attempt has its own folder, so nothing an earlier attempt still writes can land in this one.
        val staging = File(folder.parentFile, "$STAGING_PREFIX${UUID.randomUUID()}")
        try {
            withContext(io) {
                if (!staging.mkdirs()) throw IOException("Could not create a folder for the chapter")
            }
            val pages = fill(staging).ifEmpty { throw IOException("The chapter has no pages") }
            lock.withLock {
                currentCoroutineContext().ensureActive()
                loadLocked()
                withContext(io + NonCancellable) {
                    val marker = ChapterMarker(id.source.id, id.address, entry, pages.map { ChapterPageFile(it.name, it.length()) })
                    File(staging, MARKER_FILE).writeText(DurableJson.encodeToString(marker))
                    discard(folder)
                    if (!staging.renameTo(folder)) throw IOException("Could not finish saving the chapter")
                }
                index.update { chapters -> chapters.orEmpty() + (id to (chapters?.get(id).orEmpty() + entry)) }
            }
        } finally {
            withContext(io + NonCancellable) { staging.deleteRecursively() }
        }
    }

    /** Deletes the chapter at [entry], and [id]'s folder with it once no other chapter is left in it. */
    suspend fun delete(id: SeriesId, entry: String) {
        lock.withLock {
            loadLocked()
            withContext(io) {
                discard(chapterDirectory(id, entry))
                seriesDirectory(id).delete()
            }
            index.update { chapters ->
                val left = chapters?.get(id).orEmpty() - entry
                if (left.isEmpty()) chapters.orEmpty() - id else chapters.orEmpty() + (id to left)
            }
        }
    }

    /** Deletes everything downloaded for [id], chapters its source no longer lists included. */
    suspend fun deleteSeries(id: SeriesId) {
        lock.withLock {
            loadLocked()
            withContext(io) { discard(seriesDirectory(id)) }
            index.update { it.orEmpty() - id }
        }
    }

    /** Deletes every series' downloads; one stopped midway leaves each series either whole or gone. */
    suspend fun deleteAll() {
        lock.withLock {
            withContext(io) { root.listFiles().orEmpty().forEach(::discard) }
            index.value = emptyMap()
        }
    }

    /** The bytes every download takes, unfinished ones included. */
    suspend fun sizeBytes(): Long = withContext(io) { root.walk().filter(File::isFile).sumOf(File::length) }

    /** Deletes what stopped downloads and deletes left, such as by the process ending. Call while no download runs. */
    suspend fun removeUnfinished() {
        withContext(io) {
            val folders = root.listFiles().orEmpty().flatMap { listOf(it) + it.listFiles().orEmpty() }
            folders.filter { it.name.startsWith(STAGING_PREFIX) || it.name.startsWith(DELETED_PREFIX) }.forEach(File::deleteRecursively)
        }
    }

    /** [index], read from the files the first time. Must hold [lock]. */
    private suspend fun loadLocked(): Map<SeriesId, Set<String>> = index.value ?: withContext(io) {
        root.listFiles().orEmpty().asSequence()
            .flatMap { it.listFiles().orEmpty().asSequence() }
            .mapNotNull { folder -> folder.marker()?.let { marker -> marker.seriesId()?.let { it to marker.address } } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
    }.also { index.value = it }

    /** Moves [folder] out of place in one step, then deletes it. */
    private fun discard(folder: File) {
        if (!folder.exists()) return
        val deleted = File(folder.parentFile, "$DELETED_PREFIX${UUID.randomUUID()}")
        if (!folder.renameTo(deleted)) throw IOException("Could not delete the download")
        deleted.deleteRecursively()
    }

    private fun seriesDirectory(id: SeriesId) = File(root, "${id.source.id}\n${id.address}".toByteArray().sha256())

    private fun chapterDirectory(id: SeriesId, entry: String) = File(seriesDirectory(id), entry.toByteArray().sha256())

    /** The marker of the chapter this folder holds whole; null for any other folder, or one deleted while it is read. */
    private fun File.marker(): ChapterMarker? {
        if (name.startsWith(STAGING_PREFIX) || name.startsWith(DELETED_PREFIX)) return null
        val marker = try {
            DurableJson.decodeFromString<ChapterMarker>(File(this, MARKER_FILE).readText())
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return marker?.takeIf { it.pages.isNotEmpty() && it.pages.all { page -> File(this, page.name).length() == page.bytes } }
    }

    companion object {
        /** Page [index] (from 0) as a file with [extension]; numbered from 1 so the folder reads in order. */
        fun pageFileName(index: Int, extension: String): String = "${(index + 1).toString().padStart(PAGE_DIGITS, '0')}.$extension"

        private const val PAGE_DIGITS = 4
        private const val MARKER_FILE = "chapter.json"
        private const val STAGING_PREFIX = ".partial-"
        private const val DELETED_PREFIX = ".deleted-"
    }
}

/**
 * What a finished chapter folder holds: the series and chapter it is, so the files alone say what is downloaded, and
 * its pages in reading order, each with its size.
 */
@Serializable
private data class ChapterMarker(
    @SerialName("source") val source: String,
    @SerialName("series") val series: String,
    @SerialName("address") val address: String,
    @SerialName("pages") val pages: List<ChapterPageFile>,
) {
    fun seriesId() = SourceKey.parse(source)?.let { SeriesId(it, series) }
}

@Serializable
private data class ChapterPageFile(@SerialName("name") val name: String, @SerialName("bytes") val bytes: Long)
