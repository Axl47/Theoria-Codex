package com.theoriacodex.data.android.room

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RetainedPostPayloadsTest {
    @Test fun `unrelated invalidations decode zero posts and one changed payload decodes once at every library size`() {
        for (size in listOf(1_000, 10_000, 50_000)) {
            var decoded = 0
            val projection = RetainedPostPayloads { row ->
                decoded++
                Post(PostId(SourceKey.PIXIV, row.sourcePostId), ImageRef(null, null, null), null,
                    pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
                    authorName = row.payloadJson, createdAtEpochMs = null)
            }
            val rows = (0 until size).map { PostEntity("PIXIV", it.toString(), "payload$it") }
            val first = projection.project(rows)
            val unchanged = projection.project(rows.map { PostEntity(it.source, it.sourcePostId, it.payloadJson) })
            assertEquals(size, decoded)
            assertSame(first.first(), unchanged.first())
            val changed = rows.toMutableList().apply { this[0] = PostEntity("PIXIV", "0", "changed") }
            val updated = projection.project(changed)
            assertEquals(size + 1, decoded)
            assertSame(first.last(), updated.last())
        }
    }

    @Test fun `equal Java string hashes do not grant authority to a different payload`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        var decoded = 0
        val projection = RetainedPostPayloads { row ->
            decoded++
            Post(PostId(SourceKey.PIXIV, row.sourcePostId), ImageRef(null, null, null), null,
                pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
                authorName = row.payloadJson, createdAtEpochMs = null)
        }
        projection.project(listOf(PostEntity("PIXIV", "1", "Aa")))
        assertEquals("BB", projection.project(listOf(PostEntity("PIXIV", "1", "BB"))).single().authorName)
        assertEquals(2, decoded)
    }

    @Test fun `returning to a recent Codex reuses its posts while oversized projections are not retained`() = runTest {
        var decoded = 0
        val cache = RetainedPostPayloadCache(decode = { row -> decoded++; testPost(row) }, maxRetainedPosts = 3)
        val small = (0 until 3).map { PostEntity("PIXIV", "s$it", "payload$it") }
        val large = (0 until 4).map { PostEntity("PIXIV", "l$it", "payload$it") }

        val first = flowOf(small).decodePostSnapshots(cache, "codex:small").first()
        val returned = flowOf(small).decodePostSnapshots(cache, "codex:small").first()
        assertEquals(3, decoded)
        assertSame(first.first(), returned.first())

        flowOf(large).decodePostSnapshots(cache, "codex:large").first()
        flowOf(large).decodePostSnapshots(cache, "codex:large").first()
        assertEquals(3 + 8, decoded)
        flowOf(small).decodePostSnapshots(cache, "codex:small").first()
        assertEquals(3 + 8, decoded)
    }

    private fun testPost(row: PostEntity) = Post(PostId(SourceKey.PIXIV, row.sourcePostId), ImageRef(null, null, null),
        null, pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = row.payloadJson, createdAtEpochMs = null)
}
