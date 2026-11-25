package com.theoriacodex.data.android.room

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RetainedPostPayloadsTest {
    @Test fun `unrelated invalidations decode zero posts and one changed payload decodes once at every library size (ADR 0013)`() {
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

    @Test fun `equal Java string hashes do not grant authority to a different payload (ADR 0013)`() {
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

    @Test fun `returning to a recent Codex reuses its posts while oversized projections are not retained (ADR 0013)`() = runTest {
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

    @Test fun `the least recently used projection goes once there are more than maxEntries, and its collector keeps decoding into it (ADR 0013)`() {
        var decoded = 0
        val cache = RetainedPostPayloadCache(decode = { row -> decoded++; testPost(row) }, maxEntries = 2)
        val rows = listOf(PostEntity("PIXIV", "1", "payload"))
        val a = cache.projection("a").project(rows).also { cache.trim() }
        val heldB = cache.projection("b")
        val b = heldB.project(rows).also { cache.trim() }
        cache.projection("a")
        cache.projection("c").project(rows).also { cache.trim() }
        assertEquals(3, decoded)

        assertSame(a.single(), cache.projection("a").project(rows).single())
        assertSame(b.single(), heldB.project(rows).single())
        assertEquals(3, decoded)
        cache.projection("b").project(rows)
        assertEquals(4, decoded)
    }

    @Test fun `small projections that together pass the post cap lose the least recently used one (ADR 0013)`() {
        var decoded = 0
        val cache = RetainedPostPayloadCache(decode = { row -> decoded++; testPost(row) }, maxRetainedPosts = 3)
        val rows = (0 until 2).map { PostEntity("PIXIV", "$it", "payload$it") }
        cache.projection("a").project(rows).also { cache.trim() }
        val b = cache.projection("b").project(rows).also { cache.trim() }
        assertEquals(4, decoded)

        assertSame(b.first(), cache.projection("b").project(rows).first())
        cache.projection("a").project(rows)
        assertEquals(6, decoded)
    }

    @Test fun `only the latest projection's posts are kept for the next one (ADR 0013)`() {
        var decoded = 0
        val projection = RetainedPostPayloads { row -> decoded++; testPost(row) }
        val one = listOf(PostEntity("PIXIV", "1", "one"))

        projection.project(one)
        projection.project(listOf(PostEntity("PIXIV", "2", "two")))
        projection.project(one)

        assertEquals(3, decoded)
    }

    @Test fun `a collector whose projection was evicted keeps decoding into it (ADR 0013)`() = runBlocking {
        val decoded = AtomicInteger()
        val cache = RetainedPostPayloadCache(decode = { row -> decoded.incrementAndGet(); testPost(row) }, maxEntries = 1)
        val kept = PostEntity("PIXIV", "1", "payload")
        val rows = MutableStateFlow(listOf(kept))
        val seen = Channel<List<Post>>(Channel.UNLIMITED)
        val collecting = launch { rows.decodePostSnapshots(cache, "a").collect(seen::send) }
        withTimeout(5_000) { seen.receive() }
        cache.projection("b").project(listOf(PostEntity("PIXIV", "2", "other")))
        cache.trim()

        rows.value = listOf(kept, PostEntity("PIXIV", "3", "new"))
        withTimeout(5_000) { seen.receive() }
        collecting.cancel()

        assertEquals(3, decoded.get())
    }

    private fun testPost(row: PostEntity) = Post(PostId(SourceKey.PIXIV, row.sourcePostId), ImageRef(null, null, null),
        null, pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = row.payloadJson, createdAtEpochMs = null)
}
