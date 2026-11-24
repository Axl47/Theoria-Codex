@file:OptIn(ExperimentalSerializationApi::class)

package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.extensionSeriesStatus
import com.theoriacodex.domain.series.extensionSourceKey
import com.theoriacodex.domain.series.seriesKind
import com.theoriacodex.domain.series.storedRepoAddress
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * The wire format of the backups Mihon, TachiyomiJ2K, Anikku and the other apps of their family write (`.tachibk`:
 * gzip around protobuf), read for the series [ProfileBackupService.importSeries] adds to the Series tab. Those apps run
 * the same extensions, which name a series and its entries by the same source number and addresses, so each series
 * lands on the extension source that lists it here and keeps its read or watched state.
 *
 * A series comes along when the backup follows it or has progress in it, and so do the extension repos the backup
 * lists, so only the extensions remain to install. Categories, trackers, downloads and the apps' settings stay behind,
 * and so does the apps' local source, whose files Theoria cannot read.
 */
internal fun isTachiyomiBackup(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

/**
 * What importing reads from a Mihon-family backup: its [series] as Theoria stores them, the extension repos it lists
 * with the signing key each had, and its names for the sources those series are on.
 */
internal class TachiyomiImport(
    val series: List<BackupSeries>,
    val repos: List<NewExtensionRepo>,
    val sourceNames: Map<SourceKey, String>,
)

/** [TachiyomiImport] of a Mihon-family backup, its series checked as a profile backup's series are. */
internal fun decodeTachiyomiBackup(bytes: ByteArray, now: Long): TachiyomiImport {
    require(isTachiyomiBackup(bytes)) { "This is not a Mihon or Aniyomi backup." }
    val backup = rejectMalformedBackup { ProtoBuf.decodeFromByteArray<TachiyomiBackup>(gunzip(bytes)) }
    val series = (backup.manga.mapNotNull { it.toBackup(SeriesKind.MANGA, now) } + (backup.anime + backup.aniyomiAnime).mapNotNull { it.toBackup(SeriesKind.ANIME, now) })
        .distinctBy { it.series.series.id }
        .writable()
    require(series.isNotEmpty()) { "This backup has no series to import." }
    validateBackupSeries(series)
    // Only a repo pinned to a key comes back: an address alone would trust whatever key the repo names now. Older
    // Aniyomi backups keep installed extensions in field 106, whose package names are no address. A folder address is
    // how these apps store a repo, and ExtensionRepos.add takes it as it is.
    val repos = (backup.repos + backup.animeRepos).filter { it.url.trim().startsWith("https://") && it.signingKey.isNotBlank() }
        .map { NewExtensionRepo(it.url.trim(), it.signingKey.trim()) }
        .distinctBy { storedRepoAddress(it.url) }
    val names = backup.sources.named(SeriesKind.MANGA) + backup.animeSources.named(SeriesKind.ANIME)
    return TachiyomiImport(series, repos, names)
}

/** What importing [this] adds, for the preview and the message after it. */
internal fun TachiyomiImport.importPreview(): SeriesImportPreview {
    val (anime, manga) = series.partition { it.series.series.id.source.seriesKind == SeriesKind.ANIME }
    return SeriesImportPreview(
        manga = manga.count { it.series.followed },
        anime = anime.count { it.series.followed },
        chaptersRead = manga.sumOf { series -> series.entries.count { it.read } },
        episodesWatched = anime.sumOf { series -> series.entries.count { it.read } },
    )
}

/** The repos [this] lists that are not among [stored], compared by their stored address. */
internal fun TachiyomiImport.reposNotIn(stored: List<ExtensionRepo>): List<NewExtensionRepo> {
    val addresses = stored.mapTo(HashSet()) { it.url }
    return repos.filter { storedRepoAddress(it.url) !in addresses }
}

/** The preview of importing [this] on a device where [sourcesOn] are the extension sources that are on and [newRepos] of its repos can be added. */
internal fun TachiyomiImport.preview(sourcesOn: Set<SourceKey>, newRepos: Int): BackupPreview.Series {
    val missing = series.map { it.series.series.id.source }.filterNot { it in sourcesOn }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    return BackupPreview.Series(
        importPreview(),
        newRepos = newRepos,
        missingSources = missing.mapNotNull(sourceNames::get).distinct(),
        unnamedMissingSources = missing.count { it !in sourceNames },
    )
}

private fun List<TachiyomiSource>.named(kind: SeriesKind): Map<SourceKey, String> =
    filter { it.id > 0 && it.name.isNotBlank() }.associate { kind.extensionSourceKey(it.id) to it.name.trim() }

/** Refuses a backup that inflates past what any library holds, rather than filling memory. */
private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { input ->
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        require(output.size() + read <= MAX_TACHIYOMI_BACKUP_BYTES) { "This backup is too large to import." }
        output.write(buffer, 0, read)
    }
    output.toByteArray()
}

/**
 * [this] as a stored series of [kind], or null when it has nothing to keep: neither followed nor started, or on a
 * source no extension has (the local source is number 0). Positions convert to [StoredSeriesEntry]'s units: Mihon keeps
 * the last page's index from 0, so page 0 is untouched; Aniyomi keeps milliseconds, though its fields say seconds.
 *
 * The entry opened last is the one history read last, else the furthest one with progress. A series therefore keeps
 * one whenever it has progress, which is also what keeps an unfollowed series in Theoria's own backups. Each entry's
 * read time is its history's, and a followed series counts as followed from when the app added it, else from [now].
 */
private fun TachiyomiSeries.toBackup(kind: SeriesKind, now: Long): BackupSeries? {
    if (source <= 0) return null
    val readAt = history.filter { it.lastRead > 0 }.groupBy({ it.url }, { it.lastRead }).mapValues { (_, times) -> times.max() }
    val stored = entries.filter { it.url.isNotBlank() }.distinctBy { it.url }.map { it.toStored(kind, now, readAt[it.url]) }
    val addresses = stored.mapTo(HashSet()) { it.entry.address }
    val lastOpened = history.filter { it.url in addresses }.maxByOrNull { it.lastRead }?.url
        ?: stored.filter { it.read || it.position > 0 }.minByOrNull { it.sourceOrder }?.entry?.address
    if (!favorite && lastOpened == null) return null
    val series = Series(
        id = SeriesId(kind.extensionSourceKey(source), url),
        title = title,
        coverUrl = thumbnailUrl?.takeIf(String::isNotBlank),
        author = author?.takeIf(String::isNotBlank),
        genres = genres.map(String::trim).filter(String::isNotBlank),
        description = description?.trim()?.ifBlank { null },
        status = extensionSeriesStatus(status),
    )
    val followedAt = if (favorite) dateAdded.takeIf { it > 0 } ?: now else null
    return BackupSeries(StoredSeries(series, followed = favorite, lastOpenedEntry = lastOpened, followedAtEpochMs = followedAt), stored)
}

private fun TachiyomiEntry.toStored(kind: SeriesKind, now: Long, readAt: Long?): StoredSeriesEntry {
    val (position, length) = when (kind) {
        // Page index 0 is also an untouched chapter's, unless TachiyomiJ2K's pages left says it was opened.
        SeriesKind.MANGA -> (if (lastPosition > 0 || pagesLeft > 0) lastPosition + 1 else 0L) to (lastPosition + pagesLeft).takeIf { pagesLeft > 0 }
        SeriesKind.ANIME -> totalSeconds.takeIf { it > 0 }.let { duration -> lastPosition.coerceIn(0, duration ?: Long.MAX_VALUE) to duration }
    }
    return StoredSeriesEntry(
        entry = SeriesEntry(url, name, number.takeIf { it.isFinite() && it >= 0f }, scanlator?.takeIf(String::isNotBlank), dateUpload.takeIf { it > 0 }),
        sourceOrder = sourceOrder.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        firstSeenAtEpochMs = dateFetch.takeIf { it > 0 } ?: now,
        read = read,
        position = position,
        length = length,
        lastReadAtEpochMs = readAt,
    )
}

/**
 * The parts of a Mihon-family backup this import reads; protobuf skips the rest. Mihon and TachiyomiJ2K keep manga in
 * field 1, with their sources' names in 101 and Mihon its repos in 106; Anikku keeps anime in field 501, in the same
 * shape, with episodes where chapters are, and their sources and repos in 503 and 505. Aniyomi keeps its anime in
 * field 3, which the others leave unused; no Aniyomi backup has been checked against it yet.
 */
@Serializable
private class TachiyomiBackup(
    @ProtoNumber(1) val manga: List<TachiyomiSeries> = emptyList(),
    @ProtoNumber(3) val aniyomiAnime: List<TachiyomiSeries> = emptyList(),
    @ProtoNumber(101) val sources: List<TachiyomiSource> = emptyList(),
    @ProtoNumber(106) val repos: List<TachiyomiRepo> = emptyList(),
    @ProtoNumber(501) val anime: List<TachiyomiSeries> = emptyList(),
    @ProtoNumber(503) val animeSources: List<TachiyomiSource> = emptyList(),
    @ProtoNumber(505) val animeRepos: List<TachiyomiRepo> = emptyList(),
)

@Serializable
private class TachiyomiSource(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val id: Long = 0,
)

/** An extension repo: [url] is the folder holding its index, [signingKey] its key's SHA-256 fingerprint. */
@Serializable
private class TachiyomiRepo(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(5) val signingKey: String = "",
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
    @ProtoNumber(13) val dateAdded: Long = 0,
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
    /** Mihon's default, so a chapter it numbered 0 has no field; -1 is its "unknown". */
    @ProtoNumber(9) val number: Float = 0f,
    @ProtoNumber(10) val sourceOrder: Long = 0,
    @ProtoNumber(16) val totalSeconds: Long = 0,
    /** TachiyomiJ2K's pages from the last one read to the end, that one included: 1 for a finished chapter. */
    @ProtoNumber(800) val pagesLeft: Long = 0,
)

@Serializable
private class TachiyomiHistory(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(2) val lastRead: Long = 0,
)

/**
 * How far a backup may inflate: far above a real library's (the TachiyomiJ2K backup of 253 series and 40,000 chapters
 * inflates to 4 MB).
 */
private const val MAX_TACHIYOMI_BACKUP_BYTES = 64 * 1024 * 1024
