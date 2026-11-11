package com.theoriacodex.data.repository

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyOfflineManifestCodecTest {
    private fun manifestPost(source: String) = JsonParser.parseString(
        """
        {
          "id": {"source": "$source", "sourcePostId": "1"},
          "preview": {"url": "https://example.test/thumb.webp", "progressiveUrls": []},
          "media": [{
            "url": "https://example.test/1.webp",
            "progressiveUrls": ["https://example.test/1.webp", "https://example.test/1.jpg"]
          }]
        }
        """.trimIndent(),
    )

    @Test
    fun `gallery manifests written before fallback urls keep their copies as fallbacks`() {
        val gallery = requireNotNull(decodeLegacyOfflinePost(manifestPost("NHENTAI"))).media.single()
        assertEquals(emptyList<String>(), gallery.progressiveUrls)
        assertEquals(
            listOf("https://example.test/1.webp", "https://example.test/1.jpg"),
            gallery.fallbackUrls,
        )

        val booru = requireNotNull(decodeLegacyOfflinePost(manifestPost("GELBOORU"))).media.single()
        assertEquals(2, booru.progressiveUrls.size)
        assertEquals(emptyList<String>(), booru.fallbackUrls)
    }
}
