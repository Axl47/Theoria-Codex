package com.theoriacodex.app.tags

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchTerm

/** A tag counted across posts: its first spelling, its match [key], how many posts carry it, and the first that does. */
data class TagCount(val tag: String, val key: String, val count: Int, val from: Post)

/**
 * [post]'s tags that [includeTerm] keeps, by the match key [keyOf] gives each spelling, first spelling kept and blank
 * keys dropped. [topTags] counts these, so a caller matching posts against its counted keys uses the same rules.
 */
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

/**
 * The [limit] tags carried by the most of [posts], most common first, for chip rows of common tags (Codex detail,
 * creator pages). Only tags carried by a number of posts within [counted] are kept; [tieOrder] orders equal counts,
 * first seen by default. [includeTerm] and [keyOf] are as in [postTagKeys].
 */
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
