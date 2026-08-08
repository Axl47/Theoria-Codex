package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post

internal class CodexRemovalWorkflow(
    private val repository: CodexRepository,
) {
    suspend fun remove(
        codexId: String,
        items: List<CodexItem>,
        posts: List<Post>,
        showActionableFeedback: suspend (message: String, actionLabel: String) -> Boolean,
    ) {
        val selectedPostIds = posts.mapTo(linkedSetOf(), Post::id)
        val itemSnapshots = items.filter { item -> item.postId in selectedPostIds }
        if (itemSnapshots.isEmpty()) return

        while (true) {
            val removed = runCatchingPreservingCancellation {
                repository.removeItems(codexId, selectedPostIds)
            }.isSuccess
            if (removed) break
            if (!showActionableFeedback("Could not remove posts", "Retry")) return
        }

        val count = itemSnapshots.size
        val message = if (count == 1) "Post removed" else "$count posts removed"
        if (!showActionableFeedback(message, "Undo")) return

        while (true) {
            val restored = runCatchingPreservingCancellation {
                repository.restoreItems(itemSnapshots, posts)
            }.isSuccess
            if (restored) return
            if (!showActionableFeedback("Could not restore posts", "Retry")) return
        }
    }
}
