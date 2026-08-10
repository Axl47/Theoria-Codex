package com.theoriacodex.app.media

import coil.network.HttpException
import com.theoriacodex.domain.adapter.MediaRecoverySourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMediaRecoveryTest {
    @Test
    fun `recognizes only exact coil 404 failures including wrapped causes`() {
        assertTrue(isHttpNotFound(IllegalStateException("wrapped", httpFailure(404))))
        assertFalse(isHttpNotFound(httpFailure(403)))
        assertFalse(isHttpNotFound(httpFailure(500)))
        assertFalse(isHttpNotFound(IllegalStateException("unrelated")))
    }

    @Test
    fun `dispatches recovery to the source capability`() = runTest {
        val post = samplePost()
        val recovered = post.copy(title = "recovered")
        val adapter = RecoveryAdapter { _, _ -> recovered }

        val result = recoverRemoteMedia(FakeRegistry(adapter), post, post.preview)

        assertSame(recovered, result)
    }

    @Test
    fun `falls back to ordinary resolution when source has no recovery capability`() = runTest {
        val post = samplePost()
        val recovered = post.copy(title = "resolved")
        val adapter = PlainAdapter(recovered)

        val result = recoverRemoteMedia(FakeRegistry(adapter), post, post.preview)

        assertSame(recovered, result)
    }

    @Test
    fun `recovery cancellation propagates unchanged`() = runTest {
        val cancellation = CancellationException("cancel recovery")
        val adapter = RecoveryAdapter { _, _ -> throw cancellation }

        val failure = runCatching {
            recoverRemoteMedia(FakeRegistry(adapter), samplePost(), samplePost().preview)
        }.exceptionOrNull()

        assertSame(cancellation, failure)
    }

    private fun httpFailure(statusCode: Int): HttpException {
        val response = Response.Builder()
            .request(Request.Builder().url("https://media.example.test/file.webp").build())
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message("test")
            .build()
        return HttpException(response)
    }

    private fun samplePost() = Post(
        id = PostId(SourceKey.HITOMI, "1"),
        preview = ImageRef("https://example.test/stale.webp", null, "image/webp"),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )

    private class FakeRegistry(
        private val adapter: SourceAdapter,
    ) : SourceAdapterRegistry {
        override fun availableSources(): Set<SourceKey> = setOf(adapter.sourceKey)

        override fun adapterFor(sourceKey: SourceKey): SourceAdapter? =
            adapter.takeIf { it.sourceKey == sourceKey }

        override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = error("Not used")
    }

    private class RecoveryAdapter(
        private val recover: suspend (Post, ImageRef) -> Post?,
    ) : SourceAdapter, MediaRecoverySourceAdapter {
        override val sourceKey: SourceKey = SourceKey.HITOMI
        override val capabilities = SourceCapabilities(
            supportsSortNewest = true,
            supportsSortPopular = false,
            supportsSortTop = false,
            supportsSortRandom = false,
            supportsExcludeTagsServerSide = false,
            supportsDateRangeServerSide = false,
            supportsMinScoreServerSide = false,
            requiresCredentials = false,
        )

        override suspend fun recoverPostMedia(post: Post, failedMedia: ImageRef): Post? =
            recover(post, failedMedia)

        override suspend fun search(query: Query, pageToken: String?): Page<Post> = error("Not used")
        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = error("Not used")
        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = error("Not used")
        override suspend fun quickQuery(kind: QuickQueryKind): Query = Query(
            mode = QueryMode.Source(SourceKey.HITOMI),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        override suspend fun resolvePost(id: PostId): Post? = error("Not used")
    }

    private class PlainAdapter(
        private val resolved: Post?,
    ) : SourceAdapter {
        override val sourceKey: SourceKey = SourceKey.HITOMI
        override val capabilities = SourceCapabilities(
            supportsSortNewest = true,
            supportsSortPopular = false,
            supportsSortTop = false,
            supportsSortRandom = false,
            supportsExcludeTagsServerSide = false,
            supportsDateRangeServerSide = false,
            supportsMinScoreServerSide = false,
            requiresCredentials = false,
        )

        override suspend fun search(query: Query, pageToken: String?): Page<Post> = error("Not used")
        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = error("Not used")
        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = error("Not used")
        override suspend fun quickQuery(kind: QuickQueryKind): Query = error("Not used")
        override suspend fun resolvePost(id: PostId): Post? = resolved
    }
}
