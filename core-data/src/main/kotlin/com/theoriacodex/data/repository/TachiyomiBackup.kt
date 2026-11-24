@file:OptIn(ExperimentalSerializationApi::class)

package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.ANIYOMI_SOURCE_ID_PREFIX
import com.theoriacodex.domain.series.MIHON_SOURCE_ID_PREFIX
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.extensionSeriesStatus
import com.theoriacodex.domain.series.seriesKind
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/** What importing a Mihon-family backup adds: the series it follows by kind, and the entries it finished. */
data class TachiyomiBackupPreview(val manga: Int, val anime: Int, val chaptersRead: Int, val episodesWatched: Int)

/**
 * Imports the library of a backup made by Mihon, TachiyomiJ2K, Anikku or another app of their family (a `.tachibk`
 * file) into the Series tab. Those apps run the same extensions, which name a series and its entries by the same
 * source number and addresses, so each series lands on the extension source that lists it here and keeps its read
 * or watched state. It merges as a profile restore merges series ([ProfileBackupStore.merge]): nothing on the device is
 * removed, progress takes the further of the two sides, and importing twice adds nothing more.
 *
 * A series comes along when the backup follows it or has progress in it. Categories, trackers, downloads and the
 * apps' settings stay behind, and so does the apps' local source, whose files Theoria cannot read. A series whose
 * extension is not installed and on here shows as unavailable until it is.
 */
class TachiyomiBackupImport(
    private val store: ProfileBackupStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun preview(bytes: ByteArray): TachiyomiBackupPreview = decodeTachiyomiBackup(bytes, clock()).preview()

    /** Returns what was imported. */
    suspend fun import(bytes: ByteArray): TachiyomiBackupPreview {
        val series = decodeTachiyomiBackup(bytes, clock())
        store.merge(BackupLibrary(emptyList(), emptyList(), emptyList(), emptyList(), series = series))
        return series.preview()
    }
}

/** Whether [bytes] are gzip, as every Mihon-family backup is; Theoria's own backups are plain JSON. */
fun isTachiyomiBackup(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

/** The series of a Mihon-family backup as Theoria stores them, checked as a profile backup's series are. */
internal fun decodeTachiyomiBackup(bytes: ByteArray, now: Long): List<BackupSeries> {
    require(isTachiyomiBackup(bytes)) { "This is not a Mihon or Aniyomi backup." }
    val backup = ProtoBuf.decodeFromByteArray<TachiyomiBackup>(gunzip(bytes))
    val series = (backup.manga.mapNotNull { it.toBackup(SeriesKind.MANGA, now) } + backup.anime.mapNotNull { it.toBackup(SeriesKind.ANIME, now) })
        .distinctBy { it.series.series.id }
        .writable()
    require(series.isNotEmpty()) { "This backup has no series to import." }
    validateBackupSeries(series)
    return series
}

private fun List<BackupSeries>.preview(): TachiyomiBackupPreview {
    val (anime, manga) = partition { it.series.series.id.source.seriesKind == SeriesKind.ANIME }
    return TachiyomiBackupPreview(
        manga = manga.count { it.series.followed },
        anime = anime.count { it.series.followed },
        chaptersRead = manga.sumOf { series -> series.entries.count { it.read } },
        episodesWatched = anime.sumOf { series -> series.entries.count { it.read } },
    )
}

/** Refuses a backup that inflates past what any library holds, rather than filling memory. */
private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { input ->
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        require(output.size() <= MAX_TACHIYOMI_BACKUP_BYTES) { "This backup is too large to import." }
    }
    output.toByteArray()
}

/**
 * [this] as a stored series of [kind], or null when it has nothing to keep: neither followed nor started, or on a
 * source no extension has (the local source is number 0). Positions convert to [StoredSeriesEntry]'s units: Mihon keeps
 * the last page's index from 0, so page 0 is untouched; Aniyomi keeps milliseconds, though its fields say seconds.
 */
private fun TachiyomiSeries.toBackup(kind: SeriesKind, now: Long): BackupSeries? {
    if (source <= 0) return null
    val prefix = if (kind == SeriesKind.ANIME) ANIYOMI_SOURCE_ID_PREFIX else MIHON_SOURCE_ID_PREFIX
    val stored = entries.filter { it.url.isNotBlank() }.distinctBy { it.url }.map { it.toStored(kind, now) }
    val addresses = stored.mapTo(HashSet()) { it.entry.address }
    val lastOpened = history.filter { it.url in addresses }.maxByOrNull { it.lastRead }?.url
    if (!favorite && lastOpened == null && stored.none { it.read || it.position > 0 }) return null
    val series = Series(
        id = SeriesId(SourceKey.parseOrThrow("$prefix$source"), url),
        title = title,
        coverUrl = thumbnailUrl?.takeIf(String::isNotBlank),
        author = author?.takeIf(String::isNotBlank),
        genres = genres.map(String::trim).filter(String::isNotBlank),
        description = description?.trim()?.ifBlank { null },
        status = extensionSeriesStatus(status),
    )
    return BackupSeries(StoredSeries(series, followed = favorite, lastOpenedEntry = lastOpened), stored)
}

private fun TachiyomiEntry.toStored(kind: SeriesKind, now: Long): StoredSeriesEntry {
    val length = if (kind == SeriesKind.ANIME) totalSeconds.takeIf { it > 0 } else null
    val position = when (kind) {
        SeriesKind.MANGA -> if (lastPosition > 0) lastPosition + 1 else 0
        SeriesKind.ANIME -> lastPosition.coerceIn(0, length ?: Long.MAX_VALUE)
    }
    return StoredSeriesEntry(
        entry = SeriesEntry(url, name, number.takeIf { it >= 0f }, scanlator?.takeIf(String::isNotBlank), dateUpload.takeIf { it > 0 }),
        sourceOrder = sourceOrder.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        firstSeenAtEpochMs = dateFetch.takeIf { it > 0 } ?: now,
        read = read,
        position = position,
        length = length,
    )
}

/**
 * The parts of a Mihon-family backup this import reads; protobuf skips the rest. Mihon and TachiyomiJ2K keep manga in
 * field 1; Anikku keeps anime in field 501, in the same shape, with episodes where chapters are.
 */
@Serializable
private class TachiyomiBackup(
    @ProtoNumber(1) val manga: List<TachiyomiSeries> = emptyList(),
    @ProtoNumber(501) val anime: List<TachiyomiSeries> = emptyList(),
)

@Serializable
private class TachiyomiSeries(
    @ProtoNumber(1) val source: Long = 0,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val title: String = "",
    @ProtoNumber(5) val author: String? = null,
    @ProtoNumber(6) val description: String? = null,
    @ProtoNumber(7) val genres: List<String> = emptyList(),
    @ProtoNumber(8) val status: Int = 0,
    @ProtoNumber(9) val thumbnailUrl: String? = null,
    @ProtoNumber(16) val entries: List<TachiyomiEntry> = emptyList(),
    /** Mihon writes it only when false, so a series without it is in the library. */
    @ProtoNumber(100) val favorite: Boolean = true,
    @ProtoNumber(104) val history: List<TachiyomiHistory> = emptyList(),
)

/** A chapter, or an anime's episode: [read] is "seen" there, and [lastPosition] its last moment. */
@Serializable
private class TachiyomiEntry(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val scanlator: String? = null,
    @ProtoNumber(4) val read: Boolean = false,
    @ProtoNumber(6) val lastPosition: Long = 0,
    @ProtoNumber(7) val dateFetch: Long = 0,
    @ProtoNumber(8) val dateUpload: Long = 0,
    @ProtoNumber(9) val number: Float = -1f,
    @ProtoNumber(10) val sourceOrder: Long = 0,
    @ProtoNumber(16) val totalSeconds: Long = 0,
)

@Serializable
private class TachiyomiHistory(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(2) val lastRead: Long = 0,
)

/** Far above a real library's: the TachiyomiJ2K backup of 253 series and 40,000 chapters inflates to 4 MB. */
private const val MAX_TACHIYOMI_BACKUP_BYTES = 128 * 1024 * 1024
