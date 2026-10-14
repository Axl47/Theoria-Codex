package com.theoriacodex.app.post

import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PostTagSelectionTest {
    @Test fun `provider-equivalent display terms remove the exact stored draft term`() {
        val stored = SearchTerm("blue_hair")
        val selection = PostTagSelection(SourceKey.GELBOORU, listOf(stored), emptyList())
        assertSame(stored, selection.included(SearchTerm("Blue Hair", SearchFacet.TAG, "tag")))
        assertNull(selection.excluded(stored))
    }

    @Test fun `identical text in different facets and namespaces retains separate selection identity`() {
        val tag = SearchTerm("same")
        val artist = SearchTerm("same", SearchFacet.ARTIST, "artist")
        val female = SearchTerm("same", SearchFacet.TAG, "female")
        val selection = PostTagSelection(SourceKey.HITOMI, listOf(tag, female), listOf(artist))
        assertSame(tag, selection.included(tag))
        assertSame(female, selection.included(female))
        assertNull(selection.included(artist))
        assertSame(artist, selection.excluded(artist))
    }
}
