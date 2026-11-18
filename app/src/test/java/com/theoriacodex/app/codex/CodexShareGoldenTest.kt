package com.theoriacodex.app.codex

import com.google.gson.Gson
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexShareGoldenTest {
    // gson-export.json is what the Gson exporter wrote for these posts, HTML-escaped strings included.
    @Test
    fun `an export imports exactly like the file older builds exported`() {
        val golden = checkNotNull(javaClass.getResource("/golden/codex-share/gson-export.json")).readText()
        val current = Gson().toJson(buildCodexShareFile(TITLE, posts()))

        val imported = imported(current)
        assertEquals(imported(golden), imported)
        assertEquals(TITLE, imported.first)
        assertEquals(listOf("42", "7"), imported.second.map { it?.id?.sourcePostId })
    }

    private fun imported(json: String) = checkNotNull(parseCodexShareFile(json)).let { file ->
        file.title to file.posts.orEmpty().map(::codexSharePostSnapshot)
    }

    private fun posts() = listOf(
        testPost(
            sourcePostId = "42",
            title = "Tom & Jerry's <cut> a=b",
            media = listOf(
                ImageRef(
                    url = "https://example.com/m.webm?a=1&b=2",
                    localPath = "/data/user/0/x",
                    mime = "video/webm",
                    isAnimated = true,
                ),
            ),
            creatorProfile = CreatorProfile(SourceKey.PIXIV, "Artist é", "77", "https://example.com/u/77", "user:77"),
            taxonomy = listOf(
                PostTaxonomyTerm("landscape"),
                PostTaxonomyTerm("najar", SearchFacet.ARTIST, "artist", translation = "Najar"),
            ),
            durationMs = 5000L,
            mediaCount = 1,
        ),
        testPost(sourcePostId = "7"),
    )

    private companion object {
        const val TITLE = "Tom & Jerry's <codex>"
    }
}
