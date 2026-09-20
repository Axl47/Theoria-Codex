package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SearchMetadataSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.model.Post

/** Hydrates overlapping branches once per input identity without changing provider continuation. */
internal suspend fun List<Page<Post>>.withSearchMetadata(adapter: SourceAdapter): List<Page<Post>> {
    val metadataSource = adapter as? SearchMetadataSourceAdapter ?: return this
    val resolvedByOriginalId = flatMap { page -> page.items }
        .distinctBy(Post::id)
        .mapConcurrent(concurrency = 2) { post ->
            post.id to metadataSource.resolveSearchMetadata(post)
        }
        .toMap()
    return map { page ->
        page.copy(items = page.items.mapNotNull { post -> resolvedByOriginalId[post.id] }.distinctBy(Post::id))
    }
}
