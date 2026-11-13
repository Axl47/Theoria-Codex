package com.theoriacodex.sources.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginUrlTest {
    @Test
    fun `a page address is kept and free text is not a link`() {
        assertEquals("https://x.com/artist/status/1", originUrlOrNull(" https://x.com/artist/status/1 "))
        assertEquals("https://a.example/1", originUrlOrNull("https://a.example/1 https://b.example/2"))
        assertNull(originUrlOrNull("Comic Market 99"))
        assertNull(originUrlOrNull(""))
        assertNull(originUrlOrNull(null))
    }

    @Test
    fun `Pixiv image addresses and a known Pixiv ID become the artwork page`() {
        val artwork = "https://www.pixiv.net/artworks/98214188"

        assertEquals(artwork, originUrlOrNull("https://i.pximg.net/img-original/img/2022/05/08/23/40/37/98214188_p0.jpg"))
        assertEquals(artwork, originUrlOrNull("https://i.pximg.net/img-zip-ugoira/img/2022/05/08/23/40/37/98214188_ugoira1920x1080.zip"))
        assertEquals(artwork, originUrlOrNull("https://x.com/artist/status/1", pixivId = 98214188))
        assertEquals("https://www.pixiv.net/en/artworks/98214188", originUrlOrNull("https://www.pixiv.net/en/artworks/98214188"))
    }
}
