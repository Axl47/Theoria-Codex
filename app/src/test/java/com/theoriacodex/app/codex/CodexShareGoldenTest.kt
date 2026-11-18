package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.assertStoredFormat
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
        val golden = golden("gson-export.json")
        val current = encodeCodexShareFile(TITLE, posts())

        val imported = imported(current)
        assertEquals(imported(golden), imported)
        assertEquals(TITLE, imported.first)
        assertEquals(listOf("42", "7"), imported.second.map { it?.id?.sourcePostId })
    }

    // The Gson export holds no URL for a gallery entry, so full.json, captured from the kotlinx exporter, sets every key.
    @Test
    fun `the full export holds every stored key`() {
        val image = CodexShareImageRef("https://e.test/a?x=1&y=2", null, "image/png", listOf("p"), listOf("f"), true)
        val profile = CodexShareCreatorProfile("PIXIV", "A & B", "1", "https://e.test/u/1", "user:1")
        val snapshot = CodexSharePostSnapshot(
            preview = image,
            full = image,
            media = listOf(image),
            pageUrl = "https://e.test/p",
            width = 1,
            height = 2,
            canonicalTags = listOf("c"),
            rawTags = listOf("r"),
            taxonomy = listOf(CodexShareTaxonomyTerm("v", "ARTIST", "artist", "t")),
            authorName = "<author>",
            createdAtEpochMs = 3L,
            title = "It's",
            creatorProfile = profile,
            creatorProfiles = listOf(profile),
            durationMs = 4L,
            mediaCount = 5,
        )
        val full = CodexShareFile(2, TITLE, listOf(CodexSharePost("PIXIV", "42", snapshot)))
        assertStoredFormat(golden("full.json") to full)
    }

    @Test
    fun `local media paths are never exported`() {
        val image = CodexShareImageRef(url = "https://e.test/a", localPath = "/data/user/0/x")
        val encoded = DurableJson.encodeToString(CodexShareImageRef.serializer(), image)
        assertEquals("""{"url":"https://e.test/a"}""", encoded)
    }

    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/codex-share/$name")) { "missing golden $name" }.readText()

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
