package com.theoriacodex.domain.tags

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PixivTagsTest {
    @Test
    fun `search tokens rewrite booru spellings and keep native tags`() {
        assertEquals("hatsune miku", pixivSearchToken("hatsune_miku"))
        assertEquals("saber", pixivSearchToken("-saber_(fate) (cosplay)"))
        assertEquals("ラム(リゼロ)", pixivSearchToken("ラム(リゼロ)"))
        assertEquals("深夜の真剣お絵描き60分一本勝負_1", pixivSearchToken(" 深夜の真剣お絵描き60分一本勝負_1 "))
    }

    @Test
    fun `legacy pixiv payloads recover translation pairs from canonical order`() {
        val legacy = pixivPost(
            raw = listOf("初音ミク", "オリジナル", "猫"),
            canonical = listOf("初音ミク", "Hatsune Miku", "オリジナル", "猫", "cat"),
        )

        assertEquals(
            listOf(
                PostTaxonomyTerm("初音ミク", translation = "Hatsune Miku"),
                PostTaxonomyTerm("オリジナル"),
                PostTaxonomyTerm("猫", translation = "cat"),
            ),
            legacy.withRecoveredPixivTranslations().taxonomy,
        )
    }

    @Test
    fun `posts that already carry translations or have none are unchanged`() {
        val untranslated = pixivPost(raw = listOf("猫"), canonical = listOf("猫"))
        val current = pixivPost(raw = listOf("猫"), canonical = listOf("猫", "cat"))
            .copy(taxonomy = listOf(PostTaxonomyTerm("猫", translation = "cat")))

        assertSame(untranslated, untranslated.withRecoveredPixivTranslations())
        assertSame(current, current.withRecoveredPixivTranslations())
    }

    private fun pixivPost(raw: List<String>, canonical: List<String>) = Post(
        id = PostId(SourceKey.PIXIV, "1"),
        preview = ImageRef(url = "https://example.com/1.jpg", localPath = null, mime = "image/jpeg"),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = canonical,
        rawTags = raw,
        authorName = null,
        createdAtEpochMs = null,
    )
}
