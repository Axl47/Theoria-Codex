package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

fun trainingTagsFor(post: Post): List<String> {
    val preferred = when (post.id.source) {
        SourceKey.PIXIV -> post.rawTags.takeIf { it.isNotEmpty() } ?: post.canonicalTags
        SourceKey.GELBOORU, SourceKey.AIBOORU -> post.canonicalTags.takeIf { it.isNotEmpty() } ?: post.rawTags
    }
    return preferred
        .asSequence()
        .map { tag -> tag.trim() }
        .filter { tag -> tag.isNotBlank() }
        .distinctBy { tag -> tag.lowercase() }
        .take(MAX_TRAINING_TAGS)
        .toList()
}

private const val MAX_TRAINING_TAGS = 20
