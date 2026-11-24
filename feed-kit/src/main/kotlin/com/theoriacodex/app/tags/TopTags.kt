package com.theoriacodex.app.tags

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchTerm

data class TagCount(val tag: String, val key: String, val count: Int, val from: Post)

fun postTagKeys(
    post: Post,
    includeTerm: (SearchTerm) -> Boolean = { true },
    keyOf: (String) -> String = { it },
): Map<String, String> = buildMap {
    postActionTerms(post).filter(includeTerm).forEach { term ->
        val key = keyOf(term.value)
        if (key.isNotBlank() && key !in this) put(key, term.value)
    }
}

fun topTags(
    posts: List<Post>,
    limit: Int,
    counted: IntRange = 1..Int.MAX_VALUE,
    includeTerm: (SearchTerm) -> Boolean = { true },
    keyOf: (String) -> String = { it },
    tieOrder: Comparator<TagCount> = Comparator { _, _ -> 0 },
): List<TagCount> {
    val counts = linkedMapOf<String, TagCount>()
    posts.forEach { post ->
        postTagKeys(post, includeTerm, keyOf).forEach { (key, tag) ->
            counts[key] = counts[key]?.let { it.copy(count = it.count + 1) } ?: TagCount(tag, key, 1, post)
        }
    }
    return counts.values
        .filter { it.count in counted }
        .sortedWith(compareByDescending<TagCount> { it.count }.then(tieOrder))
        .take(limit)
}
