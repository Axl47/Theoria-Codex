package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A series in a profile backup (`library.series`): what its source said, what the user did with it, and its entries
 * with their progress. Series are stored once for the whole app, not per profile, so a backup holds them once.
 * Pages are never stored, so none are here; opening a restored chapter needs the series' extension installed and on.
 *
 * The cover is the address the source gave, as stored. Which client fetches it is decided when it is loaded, by the
 * series' own source, so a file cannot pick one; it can name the address that source's client is sent to, as it can
 * for the series and its chapters.
 */
@Serializable
internal data class BackupSeriesRecord(
    @SerialName("source") val source: String,
    @SerialName("address") val address: String,
    @SerialName("title") val title: String,
    @SerialName("coverUrl") val coverUrl: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("language") val language: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("followed") val followed: Boolean = false,
    @SerialName("readingMode") val readingMode: String? = null,
    @SerialName("lastOpenedEntry") val lastOpenedEntry: String? = null,
    @SerialName("lastCheckedAt") val lastCheckedAt: Long? = null,
    @SerialName("entries") val entries: List<BackupSeriesEntryRecord> = emptyList(),
) {
    /** A status or mode this build does not know is dropped; [validateBackupSeries] refuses what no build wrote. */
    fun toDomain(): BackupSeries {
        return BackupSeries(
            series = StoredSeries(
                series = Series(
                    id = SeriesId(SourceKey.parseOrThrow(source), address),
                    title = title,
                    coverUrl = coverUrl,
                    author = author,
                    genres = genres,
                    language = language,
                    description = description,
                    status = SeriesStatus.entries.firstOrNull { it.name == status } ?: SeriesStatus.UNKNOWN,
                ),
                followed = followed,
                readingMode = GalleryReadingMode.entries.firstOrNull { it.name == readingMode },
                lastOpenedEntry = lastOpenedEntry,
                lastCheckedAtEpochMs = lastCheckedAt,
            ),
            entries = entries.map(BackupSeriesEntryRecord::toDomain),
        )
    }

    companion object {
        fun fromDomain(backup: BackupSeries): BackupSeriesRecord = with(backup.series) {
            BackupSeriesRecord(
                source = series.id.source.id,
                address = series.id.address,
                title = series.title,
                coverUrl = series.coverUrl,
                author = series.author,
                genres = series.genres,
                language = series.language,
                description = series.description,
                status = series.status.takeUnless { it == SeriesStatus.UNKNOWN }?.name,
                followed = followed,
                readingMode = readingMode?.name,
                lastOpenedEntry = lastOpenedEntry,
                lastCheckedAt = lastCheckedAtEpochMs,
                entries = backup.entries.map(BackupSeriesEntryRecord::fromDomain),
            )
        }
    }
}

@Serializable
internal data class BackupSeriesEntryRecord(
    @SerialName("address") val address: String,
    @SerialName("name") val name: String,
    @SerialName("number") val number: Float? = null,
    @SerialName("translatorGroup") val translatorGroup: String? = null,
    @SerialName("uploadedAt") val uploadedAt: Long? = null,
    @SerialName("sourceOrder") val sourceOrder: Int,
    @SerialName("firstSeenAt") val firstSeenAt: Long,
    @SerialName("read") val read: Boolean = false,
    @SerialName("lastPage") val lastPage: Int = 0,
    @SerialName("pageCount") val pageCount: Int? = null,
) {
    fun toDomain() = StoredSeriesEntry(SeriesEntry(address, name, number, translatorGroup, uploadedAt), sourceOrder, firstSeenAt, read, lastPage, pageCount)

    companion object {
        fun fromDomain(stored: StoredSeriesEntry): BackupSeriesEntryRecord = with(stored) {
            BackupSeriesEntryRecord(
                entry.address, entry.name, entry.number, entry.translatorGroup, entry.uploadedAtEpochMs,
                sourceOrder, firstSeenAtEpochMs, read, lastPage, pageCount,
            )
        }
    }
}

/** The one check for series on both paths: a backup the app writes is one it restores. */
internal fun validateBackupSeries(all: List<BackupSeries>) {
    require(all.size <= MAX_BACKUP_SERIES && all.distinctBy { it.series.series.id }.size == all.size) { "Backup contains duplicate series" }
    all.forEach { backup ->
        val series = backup.series.series
        require(series.id.address.isNotBlank() && series.title.isNotBlank() && backup.entries.size <= MAX_BACKUP_SERIES_ENTRIES) {
            "Backup contains an invalid series"
        }
        require(backup.entries.distinctBy { it.entry.address }.size == backup.entries.size) { "Backup contains duplicate chapters" }
        require(backup.entries.all { it.entry.address.isNotBlank() && it.firstSeenAtEpochMs >= 0 && it.lastPage >= 0 && (it.pageCount ?: 1) > 0 }) {
            "Backup contains an invalid chapter"
        }
    }
}

/**
 * The series a backup writes. An extension can list a series without a title or a chapter without an address, and
 * one such row must not fail the export of everything else: a series without an address and a chapter without one are
 * left out, since nothing could find them again, and a blank title is written as the address. A restore still refuses
 * such input, which no export writes.
 */
internal fun List<BackupSeries>.writable(): List<BackupSeries> = filter { it.series.series.id.address.isNotBlank() }.map { backup ->
    val series = backup.series.series
    backup.copy(
        series = backup.series.copy(series = if (series.title.isBlank()) series.copy(title = series.id.address) else series),
        entries = backup.entries.filter { it.entry.address.isNotBlank() },
    ).withinEntryLimit()
}

/**
 * What a backup writes for a series with more entries than a restore accepts, so that one huge series cannot fail the
 * whole export: the entry Continue points at, then entries with progress, then the rest the source lists, then those
 * it dropped; within each, as the source orders them. An entry without progress carries nothing a restore needs,
 * since the source lists it again.
 */
internal fun BackupSeries.withinEntryLimit(): BackupSeries {
    if (entries.size <= MAX_BACKUP_SERIES_ENTRIES) return this
    fun tier(entry: StoredSeriesEntry) = when {
        entry.entry.address == series.lastOpenedEntry -> 0
        entry.read || entry.lastPage > 0 -> 1
        entry.sourceOrder >= 0 -> 2
        else -> 3
    }
    val kept = entries.sortedWith(compareBy(::tier).thenBy { it.sourceOrder }.thenBy { it.entry.address })
    return copy(entries = kept.take(MAX_BACKUP_SERIES_ENTRIES))
}

/** Sanity bounds: far more than anyone follows, or than a series has chapters. */
internal const val MAX_BACKUP_SERIES = 10_000
internal const val MAX_BACKUP_SERIES_ENTRIES = 20_000
