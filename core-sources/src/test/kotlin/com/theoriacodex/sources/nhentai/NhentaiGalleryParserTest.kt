package com.theoriacodex.sources.nhentai

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NhentaiGalleryParserTest {
    @Test
    fun `a listing with no pages, cover or page count is a gallery of unknown length (ADR 0006, 0044)`() {
        val post = requireNotNull(parseNhentaiGallery(JsonParser.parseString("""{"id":1,"media_id":"9"}""").asJsonObject))

        assertTrue(post.media.isEmpty())
        assertTrue(post.galleryLengthUnknown)
    }

    @Test
    fun `a listing that declares its page count knows its length (ADR 0044)`() {
        val post = requireNotNull(
            parseNhentaiGallery(JsonParser.parseString("""{"id":1,"media_id":"9","num_pages":12}""").asJsonObject),
        )

        assertEquals(12, post.mediaCount)
        assertFalse(post.galleryLengthUnknown)
    }
}
