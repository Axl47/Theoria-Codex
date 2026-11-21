package com.theoriacodex.app.recommend

import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForYouWhyTargetTest {
    private val seed = mapOf(SourceKey.PIXIV to listOf("boats", "sea"))
    private val hidden = listOf(ForYouBlacklistEntry(SourceKey.PIXIV, listOf("boats")))

    @Test fun `a drilled-in interest whose tag was hidden returns to the shelves`() {
        assertEquals(ForYouAction.ShowHome, ForYouWhyTarget("boats + sea", seed, "PIXIV:boats+sea").afterClose(false, hidden))
    }

    @Test fun `a drilled-in mix whose tag was hidden reshuffles`() {
        assertEquals(ForYouAction.Refresh(shuffle = true), ForYouWhyTarget("Your mix", seed, null).afterClose(false, hidden))
    }

    @Test fun `nothing follows when nothing shown was hidden or the shelves already updated`() {
        val target = ForYouWhyTarget("boats + sea", seed, "PIXIV:boats+sea")
        assertNull(target.afterClose(false, emptyList()))
        assertNull(target.afterClose(true, hidden))
    }
}
