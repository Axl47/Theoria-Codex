package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.MAX_MEMO_LENGTH
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
    @SerialName("downloadNewChapters") val downloadNewChapters: Boolean = false,
    @SerialName("followedAt") val followedAt: Long? = null,
    @SerialName("memo") val memo: String? = null,
    @SerialName("entries") val entries: List<BackupSeriesEntryRecord> = emptyList(),
) {
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
                    memo = storedMemo(memo),
                ),
                followed = followed,
                readingMode = GalleryReadingMode.entries.firstOrNull { it.name == readingMode },
                lastOpenedEntry = lastOpenedEntry,
                downloadNewChapters = downloadNewChapters,
                followedAtEpochMs = followedAt,
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
                downloadNewChapters = downloadNewChapters,
                followedAt = followedAtEpochMs,
                memo = series.memo,
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
    @SerialName("lastPage") val position: Long = 0,
    @SerialName("pageCount") val length: Long? = null,
    @SerialName("lastReadAt") val lastReadAt: Long? = null,
    @SerialName("memo") val memo: String? = null,
) {
    fun toDomain() = StoredSeriesEntry(
        SeriesEntry(address, name, number, translatorGroup, uploadedAt, storedMemo(memo)),
        sourceOrder, firstSeenAt, read, position, length, lastReadAt,
    )

    companion object {
        fun fromDomain(stored: StoredSeriesEntry): BackupSeriesEntryRecord = with(stored) {
            BackupSeriesEntryRecord(
                entry.address, entry.name, entry.number, entry.translatorGroup, entry.uploadedAtEpochMs,
                sourceOrder, firstSeenAtEpochMs, read, position, length, lastReadAtEpochMs, entry.memo,
            )
        }
    }
}

@Suppress("StoredJsonUsesDurableJsonRule")
internal fun storedMemo(text: String?): String? = text?.takeIf { it.isNotBlank() && it.length <= MAX_MEMO_LENGTH }
    ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject }
    ?.takeIf(JsonObject::isNotEmpty)?.toString()

internal fun validateBackupSeries(all: List<BackupSeries>) {
    require(all.size <= MAX_BACKUP_SERIES && all.distinctBy { it.series.series.id }.size == all.size) { "Backup contains duplicate series" }
    all.forEach { backup ->
        val series = backup.series.series
        require(series.id.address.isNotBlank() && series.title.isNotBlank() && backup.entries.size <= MAX_BACKUP_SERIES_ENTRIES) {
            "Backup contains an invalid series"
        }
        require(backup.entries.distinctBy { it.entry.address }.size == backup.entries.size) { "Backup contains duplicate chapters" }
        require(backup.entries.all { it.entry.address.isNotBlank() && it.firstSeenAtEpochMs >= 0 && it.position >= 0 && (it.length ?: 1) > 0 }) {
            "Backup contains an invalid chapter"
        }
    }
}

internal fun List<BackupSeries>.writable(): List<BackupSeries> = filter { it.series.series.id.address.isNotBlank() }.map { backup ->
    val series = backup.series.series
    backup.copy(
        series = backup.series.copy(series = if (series.title.isBlank()) series.copy(title = series.id.address) else series),
        entries = backup.entries.filter { it.entry.address.isNotBlank() },
    ).withinEntryLimit()
}

internal fun BackupSeries.withinEntryLimit(): BackupSeries {
    if (entries.size <= MAX_BACKUP_SERIES_ENTRIES) return this
    fun tier(entry: StoredSeriesEntry) = when {
        entry.entry.address == series.lastOpenedEntry -> 0
        entry.hasProgress -> 1
        entry.sourceOrder >= 0 -> 2
        else -> 3
    }
    val kept = entries.sortedWith(compareBy(::tier).thenBy { it.sourceOrder }.thenBy { it.entry.address })
    return copy(entries = kept.take(MAX_BACKUP_SERIES_ENTRIES))
}

internal const val MAX_BACKUP_SERIES = 10_000
internal const val MAX_BACKUP_SERIES_ENTRIES = 20_000
