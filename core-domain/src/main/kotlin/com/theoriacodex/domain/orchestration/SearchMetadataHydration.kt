package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SearchMetadataSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.model.Post

/**
 * Hydrates overlapping branches once per input identity without changing provider continuation.
 *
 * A post whose detail page cannot be parsed is dropped: its tags cannot be verified, so it must not
 * reach local include/exclude predicates. Its siblings still publish. Source-wide failures (network,
 * auth, rate limits), or every post failing, still fail the page.
 */
internal suspend fun List<Page<Post>>.withSearchMetadata(adapter: SourceAdapter): List<Page<Post>> {
    val metadataSource = adapter as? SearchMetadataSourceAdapter ?: return this
    val outcomes = flatMap { page -> page.items }
        .distinctBy(Post::id)
        .mapConcurrent(concurrency = 2) { post ->
            post.id to try {
                Result.success(metadataSource.resolveSearchMetadata(post))
            } catch (error: SourceAdapterException) {
                if (error.reason != SourceFailureReason.PARSE) throw error
                Result.failure(error)
            }
        }
    val failures = outcomes.mapNotNull { (_, outcome) -> outcome.exceptionOrNull() }
    if (failures.size == outcomes.size) failures.firstOrNull()?.let { throw it }
    val resolvedByOriginalId = outcomes.mapNotNull { (id, outcome) -> outcome.getOrNull()?.let { id to it } }.toMap()
    return map { page ->
        page.copy(items = page.items.mapNotNull { post -> resolvedByOriginalId[post.id] }.distinctBy(Post::id))
    }
}
