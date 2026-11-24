package com.theoriacodex.domain.series

import com.theoriacodex.domain.model.GalleryReadingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesReadingModeTest {
    @Test
    fun `the user's choice wins over every guess`() {
        assertEquals(GalleryReadingMode.LEFT_TO_RIGHT, mode(chosen = GalleryReadingMode.LEFT_TO_RIGHT, genres = listOf("Webtoon"), language = "ko"))
    }

    @Test
    fun `a long-strip genre reads vertically (ADR 0007)`() {
        for (genre in listOf("Long Strip", "Webtoons", "Manhwa", "manhua")) {
            assertEquals(genre, GalleryReadingMode.VERTICAL, mode(genres = listOf("Action", genre)))
        }
    }

    @Test
    fun `a Korean or Chinese source reads vertically (ADR 0007)`() {
        for (language in listOf("ko", "zh", "zh-Hans", "ZH-hant")) {
            assertEquals(language, GalleryReadingMode.VERTICAL, mode(language = language))
        }
    }

    @Test
    fun `anything else reads right to left`() {
        assertEquals(GalleryReadingMode.RIGHT_TO_LEFT, mode(genres = listOf("Action", "Comedy"), language = "en"))
        assertEquals(GalleryReadingMode.RIGHT_TO_LEFT, mode(language = "ja"))
        assertEquals(GalleryReadingMode.RIGHT_TO_LEFT, mode())
    }

    private fun mode(
        chosen: GalleryReadingMode? = null,
        genres: List<String> = emptyList(),
        language: String = "",
    ) = seriesReadingMode(chosen, genres, language)
}
