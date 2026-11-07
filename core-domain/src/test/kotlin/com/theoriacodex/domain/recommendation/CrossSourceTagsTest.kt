package com.theoriacodex.domain.recommendation

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossSourceTagsTest {
    private val dictionary = PixivTagDictionary.from(
        listOf("初音ミク" to "Hatsune Miku", "猫" to "cat", "ネコ" to "cat"),
    )

    @Test
    fun `concept keys ignore booru spelling, width and escaping`() {
        assertEquals("hatsune miku", crossSourceTagKey("Hatsune_Miku"))
        assertEquals("fgo", crossSourceTagKey("ＦＧＯ"))
        assertEquals("jojo's bizarre adventure", crossSourceTagKey("jojo&#039;s_bizarre_adventure"))
    }

    @Test
    fun `pixiv likes teach booru sources through translations`() {
        val training = CrossSourceTraining(
            likes = listOf(LikedTagDocument(SourceKey.PIXIV, listOf("初音ミク", "未翻訳", "猫"))),
            dictionary = dictionary,
        )

        assertEquals(listOf(listOf("hatsune_miku", "cat")), training.documentsFor(SourceKey.DANBOORU))
        assertEquals(listOf(listOf("初音ミク", "未翻訳", "猫")), training.documentsFor(SourceKey.PIXIV))
    }

    @Test
    fun `booru likes reach pixiv only through the dictionary and reach other boorus directly`() {
        val training = CrossSourceTraining(
            likes = listOf(LikedTagDocument(SourceKey.GELBOORU, listOf("hatsune_miku", "cat", "blue_hair"))),
            dictionary = dictionary,
        )

        assertEquals(listOf(listOf("初音ミク", "猫")), training.documentsFor(SourceKey.PIXIV))
        assertEquals(listOf(listOf("hatsune_miku", "cat", "blue_hair")), training.documentsFor(SourceKey.RULE34XXX))
    }

    @Test
    fun `foreign documents never outnumber native ones beyond the cold-start floor`() {
        val native = List(30) { LikedTagDocument(SourceKey.DANBOORU, listOf("native_$it")) }
        val foreign = List(40) { LikedTagDocument(SourceKey.GELBOORU, listOf("foreign_$it")) }
        val coldStart = CrossSourceTraining(foreign, PixivTagDictionary.Empty)
        val established = CrossSourceTraining(native + foreign, PixivTagDictionary.Empty)

        assertEquals(24, coldStart.documentsFor(SourceKey.DANBOORU).size)
        assertEquals(60, established.documentsFor(SourceKey.DANBOORU).size)
    }

    @Test
    fun `sources outside the shared vocabulary keep training on their own likes`() {
        val training = CrossSourceTraining(
            likes = listOf(
                LikedTagDocument(SourceKey.NHENTAI, listOf("big breasts")),
                LikedTagDocument(SourceKey.GELBOORU, listOf("big_breasts")),
            ),
            dictionary = PixivTagDictionary.Empty,
        )

        assertEquals(listOf(listOf("big breasts")), training.documentsFor(SourceKey.NHENTAI))
        assertEquals(listOf(listOf("big_breasts")), training.documentsFor(SourceKey.DANBOORU))
    }

    @Test
    fun `custom sites share the booru vocabulary in both directions`() {
        val custom = requireNotNull(SourceKey.parse("custom_safebooru_org"))
        val training = CrossSourceTraining(
            likes = listOf(
                LikedTagDocument(SourceKey.DANBOORU, listOf("hatsune_miku")),
                LikedTagDocument(custom, listOf("cat_ears")),
            ),
            dictionary = dictionary,
        )

        assertEquals(listOf(listOf("cat_ears"), listOf("hatsune_miku")), training.documentsFor(custom))
        assertEquals(listOf(listOf("hatsune_miku"), listOf("cat_ears")), training.documentsFor(SourceKey.DANBOORU))
        assertEquals("cat_ears", RecommendationTagNormalization.normalize(custom, "Cat Ears"))
    }
}
