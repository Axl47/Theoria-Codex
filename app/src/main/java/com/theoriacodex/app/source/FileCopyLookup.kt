package com.theoriacodex.app.source

import com.theoriacodex.domain.adapter.FileHashLookupSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.fileHash
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Finds a post's file on the user's other available sites by [fileHash], for the Viewer's "Find on other sites". */
class FileCopyLookup(
    private val registry: SourceAdapterRegistry,
    private val timeoutMs: Long = LOOKUP_TIMEOUT_MS,
) {
    /** Whether [find] can ask anyone: [post] has a file hash and another available site searches by one. */
    fun canFind(post: Post): Boolean = post.fileHash != null && lookupsFor(post).isNotEmpty()

    /** One post per other site holding [post]'s file, in presentation order. A site that fails or times out is skipped. */
    suspend fun find(post: Post): List<Post> {
        val hash = post.fileHash ?: return emptyList()
        return coroutineScope {
            lookupsFor(post).map { lookup ->
                async { runCatchingPreservingCancellation { withTimeoutOrNull(timeoutMs) { lookup.findByFileHash(hash) } }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
    }

    private fun lookupsFor(post: Post): List<FileHashLookupSourceAdapter> =
        (registry.availableSources() - post.id.source).inPresentationOrder()
            .mapNotNull { source -> registry.adapterFor(source) as? FileHashLookupSourceAdapter }

    private companion object {
        /** Matches the other single-request source calls (creator pages, related posts). */
        const val LOOKUP_TIMEOUT_MS = 15_000L
    }
}
