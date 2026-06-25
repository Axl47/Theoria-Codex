package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

internal data class CodexShareFile(
    val version: Int = 1,
    val title: String,
    val posts: List<CodexSharePost>,
)

internal data class CodexSharePost(
    val source: String,
    val sourcePostId: String,
)

internal fun buildCodexShareFile(title: String, posts: List<Post>): CodexShareFile {
    return CodexShareFile(
        title = title,
        posts = posts.map { post ->
            CodexSharePost(
                source = post.id.source.name,
                sourcePostId = post.id.sourcePostId,
            )
        },
    )
}

internal fun codexSharePostId(post: CodexSharePost): PostId? {
    val source = post.source
        .trim()
        .uppercase()
        .let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
        ?: return null
    val sourcePostId = post.sourcePostId.trim()
    if (sourcePostId.isBlank()) return null
    return PostId(source = source, sourcePostId = sourcePostId)
}

internal fun sanitizeCodexExportName(name: String): String {
    val normalized = name
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
    return normalized.ifBlank { "codex" }
}
