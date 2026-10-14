package com.theoriacodex.sources.common

import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Explicit counted providers can reuse autocomplete; prefix neighbors never become exact counts. */
internal suspend fun autocompleteTagCounts(
    source: SourceKey,
    tags: List<String>,
    autocomplete: suspend (String, Int) -> List<TagSuggestion>,
): Map<String, Int> = coroutineScope {
    val permits = Semaphore(2)
    tags.map(String::trim).filter(String::isNotBlank).distinctBy { sourceTagKey(source, it) }.map { tag ->
        async {
            permits.withPermit {
                autocomplete(tag, 30).firstOrNull { sourceTagKey(source, it.text) == sourceTagKey(source, tag) }
                    ?.count?.takeIf { it >= 0 }?.let { tag to it }
            }
        }
    }.awaitAll().filterNotNull().toMap()
}
