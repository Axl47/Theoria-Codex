package com.theoriacodex.domain.series

import com.theoriacodex.domain.model.SourceKey

/** What a series is made of. It decides how an entry opens and what unit progress through it counts in. */
enum class SeriesKind {
    /** Entries are chapters of pages; progress is a page number (from 1) out of a page count. */
    MANGA,

    /** Entries are episodes played as video; progress is a playback position out of a duration, in milliseconds. */
    ANIME,
}

/**
 * The id prefix of sources that run a loaded Mihon (manga) extension. Like [ANIYOMI_SOURCE_ID_PREFIX] it follows the
 * custom-site id grammar, so stored ids parse, and its label can never be a real site's host.
 */
const val MIHON_SOURCE_ID_PREFIX = "custom_mihon_"

/**
 * The id prefix of sources that run a loaded Aniyomi (anime) extension. Anime and manga extensions number their
 * sources the same way, so each kind needs its own prefix for two sources never to share an id.
 */
const val ANIYOMI_SOURCE_ID_PREFIX = "custom_aniyomi_"

/**
 * The kind of the series this source lists, read from its id alone: nothing is stored for it. Meaningful only for a
 * series source; any source but an Aniyomi extension's reads as [SeriesKind.MANGA].
 */
val SourceKey.seriesKind: SeriesKind
    get() = if (id.startsWith(ANIYOMI_SOURCE_ID_PREFIX)) SeriesKind.ANIME else SeriesKind.MANGA

/** Whether this source runs loaded extension code, of either kind. */
fun SourceKey.isExtensionSource(): Boolean = id.startsWith(MIHON_SOURCE_ID_PREFIX) || id.startsWith(ANIYOMI_SOURCE_ID_PREFIX)
