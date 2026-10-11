package com.theoriacodex.app.creator

import com.theoriacodex.app.source.ExternalCreatorDeepLink
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.stubs.StubAdapterRegistry
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorOpeningWorkflowTest {
    @Test fun `authoritative creator order opens without resolving the post`() = runTest {
        val first = creator("first")
        val post = testPost(creatorProfile = first).copy(creatorProfiles = listOf(first, creator("second")))
        val workflow = workflow(SourceKey.PIXIV, { error("Already authoritative") })
        assertEquals(CreatorOpeningOutcome(first), workflow.fromPost(post, setOf(SourceKey.PIXIV)))
    }

    @Test fun `legacy opening returns hydrated post for the shell to commit`() = runTest {
        val resolved = testPost(creatorProfile = creator("resolved"))
        val outcome = workflow(SourceKey.PIXIV, { resolved }).fromPost(testPost(), setOf(SourceKey.PIXIV))
        assertSame(resolved, outcome.resolvedPost)
        assertEquals(resolved.creatorProfile, outcome.creator)
    }

    @Test fun `provider failure and owned timeout return unavailable while parent cancellation propagates`() = runTest {
        assertNull(workflow(SourceKey.PIXIV, { throw java.io.IOException("offline") })
            .fromPost(testPost(), setOf(SourceKey.PIXIV)).creator)
        assertNull(workflow(SourceKey.PIXIV, { awaitCancellation() })
            .fromPost(testPost(), setOf(SourceKey.PIXIV)).creator)
        val result = runCatching {
            workflow(SourceKey.PIXIV, { throw CancellationException("departed") })
                .fromPost(testPost(), setOf(SourceKey.PIXIV))
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun `unavailable providers cannot resolve posts or creator links`() = runTest {
        val workflow = workflow(SourceKey.PIXIV, { error("Unavailable") })
        assertNull(workflow.fromPost(testPost(), emptySet()).creator)
        assertNull(workflow.fromDeepLink(link(SourceKey.PIXIV), emptySet()))
    }

    @Test fun `creator links retain native identities and Gelbooru author query`() = runTest {
        for (source in listOf(SourceKey.PIXIV, SourceKey.HITOMI, SourceKey.GELBOORU)) {
            val opened = workflow(source, { null }).fromDeepLink(link(source), setOf(source))
            assertEquals("owner", opened?.profileId)
            assertEquals(when (source) {
                SourceKey.HITOMI -> "artist:owner"
                SourceKey.GELBOORU -> "user:author_name"
                else -> "owner"
            }, opened?.uploadsQuery)
        }
    }

    private fun creator(id: String) = CreatorProfile(SourceKey.PIXIV, id, id, uploadsQuery = id)
    private fun link(source: SourceKey) = ExternalCreatorDeepLink(source, source.name, "owner", "https://example.test/owner")
    private fun workflow(source: SourceKey, resolve: suspend (PostId) -> Post?): CreatorOpeningWorkflow {
        val stubs = StubAdapterRegistry()
        val adapter = object : SourceAdapter by requireNotNull(stubs.adapterFor(source)), CreatorPostsSourceAdapter {
            override suspend fun resolvePost(id: PostId) = resolve(id)
            override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?) = Page<Post>(emptyList(), null)
        }
        val registry = object : SourceAdapterRegistry {
            override fun availableSources() = setOf(source)
            override fun adapterFor(sourceKey: SourceKey) = adapter.takeIf { sourceKey == source }
            override fun unifiedOrchestrator() = stubs.unifiedOrchestrator()
        }
        val http = object : SourceHttpClient {
            override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
                assertEquals(mapOf("page" to "account", "s" to "profile", "id" to "owner"), query)
                assertTrue(headers.isNotEmpty())
                return SourceHttpResponse(200, "<a href='?tags=user:author_name'>Posts</a>")
            }
            override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse = error("Read only")
        }
        return CreatorOpeningWorkflow(registry, http)
    }
}
