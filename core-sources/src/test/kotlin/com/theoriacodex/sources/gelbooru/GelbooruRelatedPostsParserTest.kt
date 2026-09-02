package com.theoriacodex.sources.gelbooru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GelbooruRelatedPostsParserTest {
    @Test
    fun `parser scopes ordered unique post links to more like this block`() {
        val html = """
            <html><body>
              <a href="index.php?page=post&amp;s=view&amp;id=999">Unrelated</a>
              <div>
                <div>More Like This: (Beta Temporary Feature)</div>
                <a href="index.php?page=post&amp;s=view&amp;id=100"><img src="a.jpg"></a>
                <a href="https://gelbooru.com/index.php?page=post&amp;s=view&amp;id=200"><img src="b.jpg"></a>
                <a href="index.php?page=post&amp;s=view&amp;id=200"><img src="duplicate.jpg"></a>
                <a href="index.php?page=post&amp;s=view&amp;id=300"><img src="c.jpg"></a>
                <a href="https://saucenao.com/search.php?id=400">Similar</a>
              </div>
            </body></html>
        """.trimIndent()

        val ids = parseGelbooruRelatedPostIds(html, seedId = "100", limit = 2)

        assertEquals(listOf("200", "300"), ids)
    }

    @Test
    fun `parser distinguishes absent block from present empty block`() {
        assertNull(parseGelbooruRelatedPostIds("<html><body></body></html>", seedId = "1", limit = 6))
        assertEquals(
            emptyList<String>(),
            parseGelbooruRelatedPostIds(
                "<div><div>More Like This: (Beta Temporary Feature)</div></div>",
                seedId = "1",
                limit = 6,
            ),
        )
    }
}
