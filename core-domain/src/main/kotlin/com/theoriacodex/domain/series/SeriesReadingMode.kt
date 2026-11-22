package com.theoriacodex.domain.series

import com.theoriacodex.domain.model.GalleryReadingMode

/**
 * How a series' entries are read: the user's [chosen] mode; else one continuous column when the series looks like a
 * long-strip comic; else right to left, as manga is. Sources do not say which kind a series is, so the guess reads
 * its genres and its source's language, both stored with the series: no extension has to load to open a chapter.
 */
fun seriesReadingMode(
    chosen: GalleryReadingMode?,
    genres: List<String>,
    sourceLanguage: String,
): GalleryReadingMode = chosen ?: when {
    genres.any { text -> LONG_STRIP_WORDS.any { word -> text.contains(word, ignoreCase = true) } } ->
        GalleryReadingMode.VERTICAL
    sourceLanguage.substringBefore('-').lowercase() in LONG_STRIP_LANGUAGES -> GalleryReadingMode.VERTICAL
    else -> GalleryReadingMode.RIGHT_TO_LEFT
}

private val LONG_STRIP_WORDS = listOf("long strip", "webtoon", "manhwa", "manhua")

/** Korean and Chinese comics are published as long strips. */
private val LONG_STRIP_LANGUAGES = setOf("ko", "zh")
