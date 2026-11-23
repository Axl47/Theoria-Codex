package com.theoriacodex.domain.series

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.SourceKey

/** A series is one address on one source; the same work on another source is another series. */
data class SeriesId(val source: SourceKey, val address: String)

/** Where a series stands, as its source says; [UNKNOWN] when it does not say. */
enum class SeriesStatus {
    UNKNOWN,
    ONGOING,
    COMPLETED,

    /** Taken up by a publisher, so the source stopped adding to it. */
    LICENSED,

    /** The work itself has ended; its release on the source has not caught up. */
    PUBLISHING_FINISHED,
    CANCELLED,
    ON_HIATUS,

    /** Announced, with nothing released yet. */
    UPCOMING,
}

/** A work read or watched in order over time, such as a manga or an anime, as its source describes it. */
data class Series(
    val id: SeriesId,
    val title: String,
    /** As the source gave it; [SeriesSource.coverAddress] is what an image loader is handed. */
    val coverUrl: String? = null,
    val author: String? = null,
    val genres: List<String> = emptyList(),
    /** The source's language code, such as `en` or `ko`; blank when the source names none. */
    val language: String = "",
    /** The synopsis; null when the source gave none, which a listing never does. */
    val description: String? = null,
    val status: SeriesStatus = SeriesStatus.UNKNOWN,
)

/** One instalment of a [Series]: a chapter or an episode. [address] identifies it within its series. */
data class SeriesEntry(
    val address: String,
    val name: String,
    /** The source's own numbering, when it has one; names are what the app shows. */
    val number: Float? = null,
    val translatorGroup: String? = null,
    val uploadedAtEpochMs: Long? = null,
)

data class SeriesPage(val series: List<Series>, val hasNextPage: Boolean)

/** What an entry opens as. [SeriesSource.media] returns the kind its source's [SeriesSource.kind] serves. */
sealed interface EntryMedia {
    /** A chapter's pages, in reading order. */
    data class Pages(val pages: List<ImageRef>) : EntryMedia

    /** An episode's ways to play, in the source's preferred order: the first is the default. */
    data class Streams(val streams: List<MediaStream>) : EntryMedia
}

/**
 * A provider of series. Every call can fail with a
 * [com.theoriacodex.domain.adapter.SourceAdapterException], as the post sources' calls do.
 */
interface SeriesSource {
    val key: SourceKey
    val name: String

    /** Decided by [key] alone, so it is known with no extension loaded and after the extension is gone. */
    val kind: SeriesKind get() = key.seriesKind

    /** The language code of everything this source lists; blank when it names none. */
    val language: String

    /** Pages count from 1. */
    suspend fun popular(page: Int): SeriesPage

    /** Whether [latest] lists anything. */
    val supportsLatest: Boolean get() = false

    /** The series updated most recently; asked for only when [supportsLatest]. */
    suspend fun latest(page: Int): SeriesPage = throw UnsupportedOperationException("$name lists no latest series")

    /** The search options this source offers, at their defaults; empty when it offers none. */
    suspend fun filters(): List<SeriesFilter> = emptyList()

    /** [filters] are [SeriesSource.filters] with values chosen; empty searches with the defaults. */
    suspend fun search(text: String, page: Int, filters: List<SeriesFilter> = emptyList()): SeriesPage

    /** [series] with what its own page adds; what the source leaves out keeps the value [series] has. */
    suspend fun details(series: Series): Series

    /** In the source's order, which lists the newest first. */
    suspend fun entries(series: Series): List<SeriesEntry>

    /**
     * [entry]'s pages, or its streams for an anime source. Addresses can expire within minutes, so they are asked for
     * each time an entry opens and never stored. A caller that can use only one kind fails on the other rather than
     * reading it as empty.
     */
    suspend fun media(entry: SeriesEntry): EntryMedia

    /**
     * The address to load [series]' cover from. A source whose images only its own client can fetch marks the address
     * here, each time it is loaded, so the stored cover stays what the source gave. Makes no request.
     */
    fun coverAddress(series: Series): String? = series.coverUrl
}
