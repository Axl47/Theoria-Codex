package com.theoriacodex.domain.tags

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTagNormalizationTest {
    @Test
    fun `hitomi tags match across provider underscores and display spaces`() {
        assertEquals("full color", sourceTagKey(SourceKey.HITOMI, " Full_Color "))
        assertTrue(sourceTagsMatch(SourceKey.HITOMI, "full_color", "Full Color"))
    }

    @Test
    fun `html entities decode once and escaped booru tags match their real names`() {
        assertEquals("jojo's_bizarre_adventure", decodeHtmlEntities("jojo&#039;s_bizarre_adventure"))
        assertEquals("a&b <c> \"d\" 'e'", decodeHtmlEntities("a&amp;b &lt;c&gt; &quot;d&quot; &#x27;e&apos;"))
        assertEquals("&#039; &unknown; & plain", decodeHtmlEntities("&amp;#039; &unknown; & plain"))
        assertTrue(sourceTagsMatch(SourceKey.GELBOORU, "jojo&#039;s_bizarre_adventure", "jojo's bizarre adventure"))
        assertEquals("jojo's_bizarre_adventure", sourceTagKey(SourceKey.RULE34XXX, "jojo&#039;s_bizarre_adventure"))
    }

    @Test
    fun `custom sources spell tags the booru way`() {
        val custom = requireNotNull(SourceKey.parse("custom_safebooru_org"))

        assertEquals(TagDialect.UNDERSCORE, custom.tagDialect)
        assertEquals("cat_ears", sourceTagKey(custom, " Cat Ears "))
        assertEquals("cat_ears", sourceAutocompletePrefix(custom, "Cat Ears"))
        assertEquals("cat_ears", normalizeFavoriteTagForStorage(custom, "Cat Ears"))
        assertTrue(sourceTagsMatch(custom, "cat ears", "CAT_EARS"))
        assertEquals(
            listOf(TagDialect.SPACED, TagDialect.UNDERSCORE, TagDialect.UNDERSCORE, TagDialect.SPACED),
            listOf(SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.IWARA, SourceKey.RULE34PAHEAL).map { it.tagDialect },
        )
    }
}
