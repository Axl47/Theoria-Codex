package com.theoriacodex.app.related

import com.theoriacodex.domain.adapter.RelatedPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

interface RelatedPostsLoading {
    fun supports(source: SourceKey): Boolean
    suspend fun load(seed: PostId): List<Post>
}

class RelatedPostsLoader(
    private val registry: SourceAdapterRegistry,
) : RelatedPostsLoading {
    override fun supports(source: SourceKey): Boolean {
        return registry.adapterFor(source) is RelatedPostsSourceAdapter
    }

    override suspend fun load(seed: PostId): List<Post> {
        val adapter = registry.adapterFor(seed.source) as? RelatedPostsSourceAdapter ?: return emptyList()
        return adapter.relatedPosts(seed, MAX_RELATED_POSTS)
            .asSequence()
            .filter { post -> post.id.source == seed.source && post.id != seed }
            .distinctBy(Post::id)
            .take(MAX_RELATED_POSTS)
            .toList()
    }
}

internal object UnsupportedRelatedPostsLoader : RelatedPostsLoading {
    override fun supports(source: SourceKey): Boolean = false
    override suspend fun load(seed: PostId): List<Post> = emptyList()
}
