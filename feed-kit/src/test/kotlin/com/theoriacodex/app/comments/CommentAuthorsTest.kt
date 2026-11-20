package com.theoriacodex.app.comments

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentAuthorsTest {
    private val browsable = setOf(SourceKey.PIXIV)

    @Test
    fun `a browseable author opens the creator page, any other with a profile url opens the site`() {
        val onPage = CreatorProfile(SourceKey.PIXIV, "painter", profileId = "1", profileUrl = "https://site/u/1", uploadsQuery = "user:1")
        assertEquals(AuthorDestination.CreatorPage(onPage), commentAuthorDestination(onPage, browsable))

        // Not browseable here: the source is not enabled for creator pages, or the profile has no uploads query.
        assertEquals(AuthorDestination.Browser("https://site/u/1"), commentAuthorDestination(onPage, emptySet()))
        assertEquals(AuthorDestination.Browser("https://site/u/1"), commentAuthorDestination(onPage.copy(uploadsQuery = null), browsable))
    }

    @Test
    fun `no author, or one with nowhere to go, opens nothing`() {
        assertNull(commentAuthorDestination(null, browsable))
        assertNull(commentAuthorDestination(CreatorProfile(SourceKey.PIXIV, "ghost", profileUrl = " "), browsable))
    }
}
