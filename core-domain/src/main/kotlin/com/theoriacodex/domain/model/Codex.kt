package com.theoriacodex.domain.model

data class Codex(
    val codexId: String,
    val name: String,
    val createdAtEpochMs: Long,
    val automaticTags: List<CodexAutomaticTag> = emptyList(),
)

data class CodexAutomaticTag(
    val source: SourceKey,
    val tag: String,
    val groupIndex: Int = 0,
)

data class CodexItem(
    val codexId: String,
    val postId: PostId,
    val savedAtEpochMs: Long,
)
