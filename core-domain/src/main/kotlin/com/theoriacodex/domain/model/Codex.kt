package com.theoriacodex.domain.model

data class Codex(
    val codexId: String,
    val name: String,
    val createdAtEpochMs: Long,
)

data class CodexItem(
    val codexId: String,
    val postId: PostId,
    val savedAtEpochMs: Long,
)
