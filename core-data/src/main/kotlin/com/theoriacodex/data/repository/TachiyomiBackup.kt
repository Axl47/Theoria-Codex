@file:OptIn(ExperimentalSerializationApi::class)

package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SourceAvailability
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

internal fun isTachiyomiBackup(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

internal class TachiyomiImport(
    val series: List<BackupSeries>,
    val repos: List<ExtensionRepo>,
    val sourceNames: Map<SourceKey, String>,
)

internal fun decodeTachiyomiBackup(bytes: ByteArray, now: Long): TachiyomiImport {
    require(isTachiyomiBackup(bytes)) { "This is not a Mihon or Aniyomi backup." }
    val backup = rejectMalformedBackup { ProtoBuf.decodeFromByteArray<TachiyomiBackup>(gunzip(bytes)) }
    val series = (backup.manga.mapNotNull { it.toBackup(SeriesKind.MANGA, now) } + (backup.anime + backup.aniyomiAnime).mapNotNull { it.toBackup(SeriesKind.ANIME, now) })
        .distinctBy { it.series.series.id }
        .writable()
    require(series.isNotEmpty()) { "This backup has no series to import." }
    validateBackupSeries(series)
    val listed = (backup.repos + backup.animeRepos).filter { it.url.trim().startsWith("https://") && it.signingKey.isNotBlank() }
        .distinctBy { storedRepoAddress(it.url) }
    val repos = listed.map { ExtensionRepo(it.url.trim(), it.name.trim().ifBlank { it.url.trim() }, it.signingKey.trim()) }
    val names = backup.sources.named(SeriesKind.MANGA) + backup.animeSources.named(SeriesKind.ANIME)
    return TachiyomiImport(series, repos, names)
}

internal fun TachiyomiImport.importPreview(): SeriesImportPreview {
    val (anime, manga) = series.partition { it.series.series.id.source.seriesKind == SeriesKind.ANIME }
    return SeriesImportPreview(
        manga = manga.count { it.series.followed },
        anime = anime.count { it.series.followed },
        chaptersRead = manga.sumOf { series -> series.entries.count { it.read } },
        episodesWatched = anime.sumOf { series -> series.entries.count { it.read } },
    )
}

internal fun TachiyomiImport.preview(sources: SourceAvailability, newRepos: List<ExtensionRepo>): BackupPreview.Series {
    val (off, missing) = series.map { it.series.series.id.source }.filterNot { it in sources.on }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.partition { it in sources.off }
    return BackupPreview.Series(
        importPreview(),
        newRepos = newRepos.map(ExtensionRepo::name),
        turnOn = off.mapNotNull(sources.off::get).map { it.copy(extensionId = null) }.distinct(),
        missingSources = missing.mapNotNull(sourceNames::get).distinct(),
        unnamedMissingSources = missing.count { it !in sourceNames },
    )
}

private fun List<TachiyomiSource>.named(kind: SeriesKind): Map<SourceKey, String> =
    filter { it.id > 0 && it.name.isNotBlank() }.associate { kind.extensionSourceKey(it.id) to it.name.trim() }

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

private fun TachiyomiSeries.toBackup(kind: SeriesKind, now: Long): BackupSeries? {
    if (source <= 0) return null
    val readAt = history.filter { it.lastRead > 0 }.groupBy({ it.url }, { it.lastRead }).mapValues { (_, times) -> times.max() }
    val stored = entries.filter { it.url.isNotBlank() }.distinctBy { it.url }.map { it.toStored(kind, now, readAt[it.url]) }
    val addresses = stored.mapTo(HashSet()) { it.entry.address }
    val lastOpened = history.filter { it.url in addresses }.maxByOrNull { it.lastRead }?.url
        ?: stored.filter(StoredSeriesEntry::hasProgress).minByOrNull { it.sourceOrder }?.entry?.address
    if (!favorite && lastOpened == null) return null
    val series = Series(
        id = SeriesId(kind.extensionSourceKey(source), url),
        title = title,
        coverUrl = thumbnailUrl?.takeIf(String::isNotBlank),
        author = author?.takeIf(String::isNotBlank),
        genres = genres.map(String::trim).filter(String::isNotBlank),
        description = description?.trim()?.ifBlank { null },
        status = extensionSeriesStatus(status),
        memo = storedMemo(memo),
    )
    val followedAt = if (favorite) dateAdded.takeIf { it > 0 } ?: now else null
    val readingMode = if (kind == SeriesKind.MANGA) mihonReadingMode(viewerFlags ?: viewer) else null
    return BackupSeries(
        StoredSeries(series, followed = favorite, readingMode = readingMode, lastOpenedEntry = lastOpened, followedAtEpochMs = followedAt),
        stored,
    )
}

private fun mihonReadingMode(flags: Int): GalleryReadingMode? = when (flags and 0x7) {
    1 -> GalleryReadingMode.LEFT_TO_RIGHT
    2 -> GalleryReadingMode.RIGHT_TO_LEFT
    3, 4, 5 -> GalleryReadingMode.VERTICAL
    else -> null
}

private fun TachiyomiEntry.toStored(kind: SeriesKind, now: Long, readAt: Long?): StoredSeriesEntry {
    val (position, length) = when (kind) {
        SeriesKind.MANGA -> (if (lastPosition > 0 || pagesLeft > 0) lastPosition + 1 else 0L) to (lastPosition + pagesLeft).takeIf { pagesLeft > 0 }
        SeriesKind.ANIME -> totalSeconds.takeIf { it > 0 }.let { duration -> lastPosition.coerceIn(0, duration ?: Long.MAX_VALUE) to duration }
    }
    return StoredSeriesEntry(
        entry = SeriesEntry(
            url, name, number.takeIf { it.isFinite() && it >= 0f }, scanlator?.takeIf(String::isNotBlank), dateUpload.takeIf { it > 0 }, storedMemo(memo),
        ),
        sourceOrder = sourceOrder.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        firstSeenAtEpochMs = dateFetch.takeIf { it > 0 } ?: now,
        read = read,
        position = position,
        length = length,
        lastReadAtEpochMs = readAt,
    )
}

private fun storedMemo(bytes: ByteArray): String? = bytes.takeIf(ByteArray::isNotEmpty)?.let { storedMemo(it.decodeToString()) }

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

@Serializable
private class TachiyomiRepo(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(2) val name: String = "",
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
    @ProtoNumber(14) val viewer: Int = 0,
    @ProtoNumber(16) val entries: List<TachiyomiEntry> = emptyList(),
    @ProtoNumber(100) val favorite: Boolean = true,
    @ProtoNumber(103) val viewerFlags: Int? = null,
    @ProtoNumber(104) val history: List<TachiyomiHistory> = emptyList(),
    @ProtoNumber(112) val memo: ByteArray = ByteArray(0),
)

@Serializable
private class TachiyomiEntry(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val scanlator: String? = null,
    @ProtoNumber(4) val read: Boolean = false,
    @ProtoNumber(6) val lastPosition: Long = 0,
    @ProtoNumber(7) val dateFetch: Long = 0,
    @ProtoNumber(8) val dateUpload: Long = 0,
    @ProtoNumber(9) val number: Float = 0f,
    @ProtoNumber(10) val sourceOrder: Long = 0,
    @ProtoNumber(13) val memo: ByteArray = ByteArray(0),
    @ProtoNumber(16) val totalSeconds: Long = 0,
    @ProtoNumber(800) val pagesLeft: Long = 0,
)

@Serializable
private class TachiyomiHistory(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(2) val lastRead: Long = 0,
)

private const val MAX_TACHIYOMI_BACKUP_BYTES = 64 * 1024 * 1024
