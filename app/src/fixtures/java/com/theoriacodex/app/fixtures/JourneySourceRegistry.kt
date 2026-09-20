package com.theoriacodex.app.fixtures

import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.RelatedPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import java.util.concurrent.CopyOnWriteArrayList

/** Controlled external provider responses; production coordinators still own execution and paging. */
class JourneySourceRegistry(postsBySource: Map<SourceKey, List<Post>>, private val pageSize: Int) : SourceAdapterRegistry {
    data class Request(val source: SourceKey, val query: Query, val pageToken: String?)
    val requests = CopyOnWriteArrayList<Request>()
    val resolveRequests = CopyOnWriteArrayList<PostId>()
    private val adapters = postsBySource.mapValues { (source, posts) -> FixtureAdapter(source, posts) }
    override fun availableSources() = adapters.keys
    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adapters[sourceKey]
    override fun unifiedOrchestrator() = UnifiedSearchOrchestrator(adapters)

    private inner class FixtureAdapter(override val sourceKey: SourceKey, private val posts: List<Post>) :
        SourceAdapter, CreatorPostsSourceAdapter, RelatedPostsSourceAdapter {
        override val capabilities = SourceCapabilities(true, true, true, true, true, true, true, false, true)
        override suspend fun search(query: Query, pageToken: String?): Page<Post> {
            requests += Request(sourceKey, query, pageToken)
            val eligible = posts.filter { post ->
                val tags = post.canonicalTags.toSet()
                query.effectiveIncludeTermGroups.all { group -> group.terms.any { it.value in tags } } &&
                    query.excludeTags.none(tags::contains)
            }
            return page(eligible, pageToken)
        }
        override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> = page(posts, pageToken)
        override suspend fun relatedPosts(seed: PostId, limit: Int) = posts.filterNot { it.id == seed }.take(limit)
        override suspend fun resolvePost(id: PostId): Post? {
            resolveRequests += id
            return posts.firstOrNull { it.id == id }
        }
        override suspend fun trendingTags(limit: Int) = suggestions().take(limit)
        override suspend fun autocompleteTags(prefix: String, limit: Int) =
            suggestions().filter { it.text.contains(prefix, ignoreCase = true) }.take(limit)
        override suspend fun quickQuery(kind: QuickQueryKind) = Query(
            QueryMode.Source(sourceKey), emptyList<String>(), emptyList(), SortMode.NEWEST, null, null,
        )
        private fun suggestions() = posts.flatMap(Post::canonicalTags).groupingBy { it }.eachCount()
            .map { (tag, count) -> TagSuggestion(tag, "tag", count) }
        private fun page(items: List<Post>, pageToken: String?): Page<Post> {
            val offset = pageToken?.toInt() ?: 0
            val next = (offset + pageSize).takeIf { it < items.size }?.toString()
            return Page(items.drop(offset).take(pageSize), next)
        }
    }
}
