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
    fun `html entities decode once and escaped booru tags match their real names (ADR 0010)`() {
        assertEquals("jojo's_bizarre_adventure", decodeHtmlEntities("jojo&#039;s_bizarre_adventure"))
        assertEquals("a&b <c> \"d\" 'e'", decodeHtmlEntities("a&amp;b &lt;c&gt; &quot;d&quot; &#x27;e&apos;"))
        assertEquals("&#039; &unknown; & plain", decodeHtmlEntities("&amp;#039; &unknown; & plain"))
        assertEquals("\uD83D\uDE00 \uD83D\uDE00", decodeHtmlEntities("&#128512; &#x1F600;"))
        assertTrue(sourceTagsMatch(SourceKey.GELBOORU, "jojo&#039;s_bizarre_adventure", "jojo's bizarre adventure"))
        assertEquals("jojo's_bizarre_adventure", sourceTagKey(SourceKey.RULE34XXX, "jojo&#039;s_bizarre_adventure"))
    }

    @Test
    fun `custom sources spell tags the booru way (ADR 0004, 0010)`() {
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

    @Test
    fun `every built-in source keeps its tag dialect (ADR 0010)`() {
        assertEquals(
            mapOf(
                "PIXIV" to TagDialect.SPACED,
                "GELBOORU" to TagDialect.UNDERSCORE,
                "AIBOORU" to TagDialect.UNDERSCORE,
                "DANBOORU" to TagDialect.UNDERSCORE,
                "NHENTAI" to TagDialect.SPACED,
                "HITOMI" to TagDialect.SPACED,
                "IWARA" to TagDialect.UNDERSCORE,
                "RULE34XXX" to TagDialect.UNDERSCORE,
                "RULE34PAHEAL" to TagDialect.SPACED,
                "RULE34VIDEO" to TagDialect.SPACED,
                "RULE34GEN" to TagDialect.SPACED,
            ),
            SourceKey.builtIns.associate { it.id to it.tagDialect },
        )
    }
}
