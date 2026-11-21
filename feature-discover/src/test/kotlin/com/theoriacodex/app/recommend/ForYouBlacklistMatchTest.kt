package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouBlacklistMatchTest {
    private val boats = listOf(ForYouBlacklistEntry(SourceKey.PIXIV, listOf("boats")))

    @Test fun `one hidden tag hides every tag set that includes it`() {
        assertTrue(boats.hidesSeed(SourceKey.PIXIV, listOf("boats")))
        assertTrue(boats.hidesSeed(SourceKey.PIXIV, listOf("sea", " Boats ")))
        assertFalse(boats.hidesSeed(SourceKey.PIXIV, listOf("sea", "harbor")))
    }

    @Test fun `a combination hides only sets holding all of its tags, on its own source`() {
        val combo = listOf(ForYouBlacklistEntry(SourceKey.PIXIV, listOf("sea", "boats")))
        assertTrue(combo.hidesSeed(SourceKey.PIXIV, listOf("boats", "sea", "sunset")))
        assertFalse(combo.hidesSeed(SourceKey.PIXIV, listOf("boats")))
        assertFalse(combo.hidesSeed(SourceKey.GELBOORU, listOf("boats", "sea")))
    }

    @Test fun `an entry without tags hides nothing`() {
        assertFalse(listOf(ForYouBlacklistEntry(SourceKey.PIXIV, listOf(" "))).hidesSeed(SourceKey.PIXIV, listOf("boats")))
    }

    @Test fun `a seed spanning sources is hidden when any source part is`() {
        assertTrue(boats.hidesAny(mapOf(SourceKey.GELBOORU to listOf("sea"), SourceKey.PIXIV to listOf("boats", "sea"))))
        assertFalse(boats.hidesAny(mapOf(SourceKey.GELBOORU to listOf("boats"))))
    }
}
