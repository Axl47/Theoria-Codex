package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.tags.normalizeMatchToken

/** Each distinct incoming tag contributes once per saved post, including across providers. */
class CodexSaveRelevance(posts: List<Post>) {
    private val tags = posts.flatMap { it.matchingTags() }.toSet()
    val hasTags: Boolean = tags.isNotEmpty()

    fun score(savedPosts: List<Post>): Long = savedPosts.distinctBy(Post::id).sumOf { post ->
        post.matchingTags().count { it in tags }.toLong()
    }
}

private fun Post.matchingTags(): Set<String> = canonicalTags.ifEmpty { rawTags }
    .map(::normalizeMatchToken).filter(String::isNotBlank).toSet()
