package com.theoriacodex.app.fixtures

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderShapedSourceTest {
    @Test
    fun theDanbooruAdapterCarriesTheGeneratedPostsAcrossThreePages() = runTest {
        val media = ImageRef(url = null, localPath = "/journey/landscape.png", mime = "image/png")
        val posts = providerShapedPosts(SourceKey.DANBOORU, 100, media)
        val source = ProviderShapedSource(posts, JourneyNoNetworkHttpClient)
        val adapter = source.adapter(SourceKey.DANBOORU)
        val query = Query(QueryMode.Source(SourceKey.DANBOORU), emptyList<String>(), emptyList(), SortMode.NEWEST, null, null)

        val pages = mutableListOf<Page<Post>>(adapter.search(query, null))
        while (pages.size < 3) pages += adapter.search(query, pages.last().nextPageToken)

        assertEquals(listOf("1", "b61", "b21"), source.pageRequests)
        assertEquals(listOf("b61", "b21", null), pages.map { it.nextPageToken })
        val parsed = pages.flatMap { it.items }
        assertEquals(posts.map { it.id }, parsed.map { it.id })
        assertEquals(posts.map { it.canonicalTags }, parsed.map { it.canonicalTags })
        assertEquals(setOf(media.localPath), parsed.flatMap { listOf(it.preview.url, it.full?.url) }.toSet())
    }

    @Test
    fun theDanbooruAdapterResolvesOnePostWithItsOrigin() = runTest {
        val media = ImageRef(url = null, localPath = "/journey/landscape.png", mime = "image/png")
        val origin = "https://danbooru.donmai.us/posts/1"
        val posts = providerShapedPosts(SourceKey.DANBOORU, 3, media)
            .map { if (it.id.sourcePostId == "2") it.copy(originUrl = origin) else it }
        val source = ProviderShapedSource(posts, JourneyNoNetworkHttpClient)
        val adapter = source.adapter(SourceKey.DANBOORU)

        val resolved = adapter.resolvePost(PostId(SourceKey.DANBOORU, "2"))

        assertEquals(listOf("2"), source.postRequests)
        assertEquals(posts[1].id, resolved?.id)
        assertEquals(origin, resolved?.originUrl)
    }
}
