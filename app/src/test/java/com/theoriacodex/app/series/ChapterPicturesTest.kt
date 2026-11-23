package com.theoriacodex.app.series

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterPicturesTest {
    private val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_2499283573021220255"), "/series")
    private val credit = ImageRef("https://pages.test/credit.jpg", null, null)

    @Test fun `a first page that failed to load is compared again the next time`() = runTest {
        var online = false
        val pictures = ChapterPictures { _, _ -> if (online) 7L else null }

        pictures.compare(id, mapOf("/c1" to credit, "/c2" to credit))
        assertEquals(0, pictures.leadPage(id).first())
        online = true
        pictures.compare(id, mapOf("/c1" to credit, "/c2" to credit))

        assertEquals(1, pictures.leadPage(id).first())
    }
}
