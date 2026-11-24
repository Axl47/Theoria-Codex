package com.theoriacodex.domain.series

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.SourceKey

data class SeriesId(val source: SourceKey, val address: String)

enum class SeriesStatus {
    UNKNOWN,
    ONGOING,
    COMPLETED,

    LICENSED,

    PUBLISHING_FINISHED,
    CANCELLED,
    ON_HIATUS,

    UPCOMING,
}

fun extensionSeriesStatus(number: Int): SeriesStatus = when (number) {
    1 -> SeriesStatus.ONGOING
    2 -> SeriesStatus.COMPLETED
    3 -> SeriesStatus.LICENSED
    4 -> SeriesStatus.PUBLISHING_FINISHED
    5 -> SeriesStatus.CANCELLED
    6 -> SeriesStatus.ON_HIATUS
    7 -> SeriesStatus.UPCOMING
    else -> SeriesStatus.UNKNOWN
}

data class Series(
    val id: SeriesId,
    val title: String,
    val coverUrl: String? = null,
    val author: String? = null,
    val genres: List<String> = emptyList(),
    val language: String = "",
    val description: String? = null,
    val status: SeriesStatus = SeriesStatus.UNKNOWN,
    val memo: String? = null,
)

data class SeriesEntry(
    val address: String,
    val name: String,
    val number: Float? = null,
    val translatorGroup: String? = null,
    val uploadedAtEpochMs: Long? = null,
    val memo: String? = null,
)

const val MAX_MEMO_LENGTH = 8_192

data class SeriesEntries(val entries: List<SeriesEntry>, val seriesMemo: String? = null) {
    fun memoOn(series: Series): Series = seriesMemo?.let { series.copy(memo = it) } ?: series
}

data class SeriesPage(val series: List<Series>, val hasNextPage: Boolean)

sealed interface EntryMedia {
    data class Pages(val pages: List<ImageRef>) : EntryMedia

    data class Streams(val streams: List<MediaStream>) : EntryMedia
}

interface SeriesSource {
    val key: SourceKey
    val name: String

    val kind: SeriesKind get() = key.seriesKind

    val language: String

    suspend fun popular(page: Int): SeriesPage

    val supportsLatest: Boolean get() = false

    suspend fun latest(page: Int): SeriesPage = throw UnsupportedOperationException("$name lists no latest series")

    suspend fun filters(): List<SeriesFilter> = emptyList()

    suspend fun search(text: String, page: Int, filters: List<SeriesFilter> = emptyList()): SeriesPage

    suspend fun details(series: Series): Series

    suspend fun entries(series: Series, stored: List<SeriesEntry>): SeriesEntries

    suspend fun media(entry: SeriesEntry): EntryMedia

    fun webUrl(series: Series): String? =
        series.id.address.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
}

fun interface SeriesCovers {
    fun address(series: Series): String?

    companion object {
        val Stored = SeriesCovers { it.coverUrl }
    }
}

class UnsupportedEntriesException(override val message: String) : Exception(message)
