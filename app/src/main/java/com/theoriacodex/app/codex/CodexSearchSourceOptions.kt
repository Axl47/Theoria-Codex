package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey

data class CodexSearchSourceOption(
    val source: SourceKey,
    val postCount: Int,
)

data class CodexSearchTagOption(
    val tag: String,
    val count: Int,
)

fun codexSearchSourceOptions(
    posts: List<Post>,
    availableSources: Set<SourceKey>,
): List<CodexSearchSourceOption> {
    if (posts.isEmpty() || availableSources.isEmpty()) return emptyList()

    val countsBySource = posts
        .asSequence()
        .map { post -> post.id.source }
        .filter { source -> source in availableSources }
        .groupingBy { source -> source }
        .eachCount()

    return SourceKey.entries
        .mapNotNull { source ->
            val count = countsBySource[source] ?: return@mapNotNull null
            CodexSearchSourceOption(source = source, postCount = count)
        }
}

fun buildSourceScopedCodexSearchTags(
    posts: List<Post>,
    source: SourceKey,
    limit: Int,
): List<String> {
    if (limit <= 0) return emptyList()
    return codexSearchTagOptions(posts = posts, source = source)
        .take(limit)
        .map { option -> option.tag }
}

fun codexSearchTagOptions(
    posts: List<Post>,
    source: SourceKey,
): List<CodexSearchTagOption> {
    if (posts.isEmpty()) return emptyList()

    val frequencies = linkedMapOf<String, CodexTagFrequency>()
    posts
        .asSequence()
        .filter { post -> post.id.source == source }
        .forEach { post ->
            val uniquePostTags = linkedMapOf<String, String>()
            for (rawTag in post.canonicalTags) {
                val tag = rawTag.trim()
                if (tag.isBlank() || tag.startsWith("-")) continue
                val key = sourceTagKey(source, tag)
                if (key.isBlank()) continue
                uniquePostTags.putIfAbsent(key, tag)
            }

            uniquePostTags.forEach { (key, tag) ->
                val current = frequencies[key]
                if (current == null) {
                    frequencies[key] = CodexTagFrequency(tag = tag, count = 1)
                } else {
                    frequencies[key] = current.copy(count = current.count + 1)
                }
            }
        }

    return frequencies
        .values
        .sortedWith(
            compareByDescending<CodexTagFrequency> { it.count }
                .thenBy { it.tag.lowercase() }
        )
        .map { frequency ->
            CodexSearchTagOption(
                tag = frequency.tag,
                count = frequency.count,
            )
        }
}

private data class CodexTagFrequency(
    val tag: String,
    val count: Int,
)
