package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.followKey
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class FollowedCodexTest {
    private val pixivOne = follow(SourceKey.PIXIV, "1")
    private val pixivTwo = follow(SourceKey.PIXIV, "2")
    private val iwaraOne = follow(SourceKey.IWARA, "1")
    private val follows = listOf(pixivOne, pixivTwo, iwaraOne)

    @Test fun `author alternatives preserve source identity even with identical names and IDs`() {
        assertEquals(listOf(pixivOne, pixivTwo), selectFollowedCreators(follows, emptySet(),
            setOf(pixivOne.creator.followKey(), pixivTwo.creator.followKey())))
        assertEquals(listOf(pixivOne, iwaraOne), selectFollowedCreators(follows, emptySet(),
            setOf(pixivOne.creator.followKey(), iwaraOne.creator.followKey())))
    }

    @Test fun `sources are alternatives intersected with authors and empty selections mean all`() {
        assertEquals(follows, selectFollowedCreators(follows, emptySet(), emptySet()))
        assertEquals(follows, selectFollowedCreators(follows, setOf("PIXIV", "IWARA"), emptySet()))
        assertEquals(listOf(iwaraOne), selectFollowedCreators(follows, setOf("IWARA"),
            setOf(pixivOne.creator.followKey(), iwaraOne.creator.followKey())))
        assertEquals(emptyList<FollowedCreator>(), selectFollowedCreators(follows, setOf("IWARA"),
            setOf(pixivOne.creator.followKey())))
    }

    private fun follow(source: SourceKey, id: String) = FollowedCreator(
        CreatorProfile(source, "Same name", id, uploadsQuery = "user:$id"), "${source.name}:$id",
    )
}
