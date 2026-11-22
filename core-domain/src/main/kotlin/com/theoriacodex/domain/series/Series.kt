package com.theoriacodex.domain.series

import com.theoriacodex.domain.model.ImageRef
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
}

/** A work read or watched in order over time, such as a manga, as its source describes it. */
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

/** One instalment of a [Series]: a chapter today. [address] identifies it within its series. */
data class SeriesEntry(
    val address: String,
    val name: String,
    /** The source's own numbering, when it has one; names are what the app shows. */
    val number: Float? = null,
    val translatorGroup: String? = null,
    val uploadedAtEpochMs: Long? = null,
)

data class SeriesPage(val series: List<Series>, val hasNextPage: Boolean)

/**
 * A provider of series. Every call can fail with a
 * [com.theoriacodex.domain.adapter.SourceAdapterException], as the post sources' calls do.
 */
interface SeriesSource {
    val key: SourceKey
    val name: String

    /** The language code of everything this source lists; blank when it names none. */
    val language: String

    /** Pages count from 1. */
    suspend fun popular(page: Int): SeriesPage

    suspend fun search(text: String, page: Int): SeriesPage

    /** [series] with what its own page adds; what the source leaves out keeps the value [series] has. */
    suspend fun details(series: Series): Series

    /** In the source's order, which lists the newest first. */
    suspend fun entries(series: Series): List<SeriesEntry>

    /** Addresses that can expire within minutes, so they are asked for each time an entry opens and never stored. */
    suspend fun pages(entry: SeriesEntry): List<ImageRef>

    /**
     * The address to load [series]' cover from. A source whose images only its own client can fetch marks the address
     * here, each time it is loaded, so the stored cover stays what the source gave. Makes no request.
     */
    fun coverAddress(series: Series): String? = series.coverUrl
}

/** The id prefix of sources that run loaded extension code. It follows the custom-site id grammar, so stored ids parse. */
const val EXTENSION_SOURCE_ID_PREFIX = "custom_mihon_"
