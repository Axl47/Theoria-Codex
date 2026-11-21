package com.theoriacodex.app.codex

import com.theoriacodex.app.tags.postActionTerms
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.tags.normalizeMatchToken

/** One of a Codex's most common tags: how it is spelled on screen, its match [key], and how many posts carry it. */
data class CodexTagCount(val tag: String, val key: String, val count: Int)

/**
 * The [limit] tags carried by the most of [posts], across every source, for Codex detail's chip row. Spellings that
 * match alike count once per post; ties keep alphabetical order. Tags every post carries filter nothing, so they
 * are left out.
 */
internal fun codexTopTags(posts: List<Post>, limit: Int = CODEX_TAG_CHIP_LIMIT): List<CodexTagCount> {
    val counts = linkedMapOf<String, CodexTagCount>()
    posts.forEach { post ->
        postTagKeys(post).forEach { (key, tag) ->
            counts[key] = counts[key]?.let { it.copy(count = it.count + 1) } ?: CodexTagCount(tag, key, 1)
        }
    }
    return counts.values
        .filter { it.count < posts.size }
        .sortedWith(compareByDescending<CodexTagCount> { it.count }.thenBy { it.tag.lowercase() })
        .take(limit)
}

/** The posts carrying the tag whose match key is [key], or every post when [key] is null. */
internal fun List<Post>.withCodexTag(key: String?): List<Post> =
    if (key == null) this else filter { post -> key in postTagKeys(post) }

/** [post]'s tags by match key, first spelling kept. Negated terms are exclusions, not tags the post carries. */
private fun postTagKeys(post: Post): Map<String, String> = postActionTerms(post)
    .filterNot { it.value.startsWith("-") }
    .map { normalizeMatchToken(it.value) to it.value }
    .filter { (key, _) -> key.isNotBlank() }
    .distinctBy { (key, _) -> key }
    .toMap()

const val CODEX_TAG_CHIP_LIMIT = 12
