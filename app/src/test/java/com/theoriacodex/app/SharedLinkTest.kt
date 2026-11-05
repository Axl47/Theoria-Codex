package com.theoriacodex.app

import com.theoriacodex.app.settings.openLinksSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinkTest {
    @Test fun `a shared link is found among a title and trailing punctuation`() {
        assertEquals("https://www.pixiv.net/artworks/1", firstWebUrl("Nice piece https://www.pixiv.net/artworks/1"))
        assertEquals("https://gelbooru.com/index.php?page=post&s=view&id=2", firstWebUrl("(see https://gelbooru.com/index.php?page=post&s=view&id=2)."))
        assertEquals("HTTP://hitomi.la/x", firstWebUrl("HTTP://hitomi.la/x"))
        assertNull(firstWebUrl("no link in this text"))
    }

    @Test fun `the links summary says which sites are on`() {
        assertEquals("Off. Provider links open in your browser", openLinksSummary(0, 17))
        assertEquals("On for 3 of 17 sites", openLinksSummary(3, 17))
        assertEquals("On for all supported sites", openLinksSummary(17, 17))
    }
}
