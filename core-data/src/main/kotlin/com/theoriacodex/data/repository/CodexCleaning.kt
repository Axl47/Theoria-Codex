package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post

/** Captured identities for one target and the union of its comparison collections. */
data class CodexCleaningRequest(
    val profileId: String,
    val targetId: String,
    val comparisonIds: Set<String>,
) {
    init {
        require(profileId.isNotBlank())
        require(comparisonIds.isNotEmpty() && targetId !in comparisonIds)
        require((comparisonIds + targetId).all { ProfileLibraryIds.belongsTo(it, profileId) })
    }
}

/** Exact removed membership and Like rows; process-only authority for Undo. */
data class CodexCleaningSnapshot(
    val request: CodexCleaningRequest,
    val items: List<CodexItem>,
    val posts: List<Post>,
    val likes: List<LikedPost>,
)
