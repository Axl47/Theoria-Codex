package com.theoriacodex.domain.series

import com.theoriacodex.domain.model.GalleryReadingMode

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

private val LONG_STRIP_LANGUAGES = setOf("ko", "zh")
