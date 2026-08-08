package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.PostId

internal data class CodexEditSelection(
    val active: Boolean = false,
    val selectedPostIds: Set<PostId> = emptySet(),
) {
    fun begin(): CodexEditSelection = CodexEditSelection(active = true)

    fun toggle(postId: PostId): CodexEditSelection {
        if (!active) return this
        val updated = selectedPostIds.toMutableSet().apply {
            if (!add(postId)) remove(postId)
        }
        return copy(selectedPostIds = updated)
    }

    fun retainAvailable(availablePostIds: Set<PostId>): CodexEditSelection {
        if (!active) return this
        return copy(selectedPostIds = selectedPostIds.intersect(availablePostIds))
    }

    fun exit(): CodexEditSelection = CodexEditSelection()
}
