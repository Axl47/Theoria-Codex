package com.theoriacodex.app.source

import com.theoriacodex.domain.adapter.FileHashLookupSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.fileHash
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Finds a post's file on the user's other available sites by [fileHash], for the Viewer's "Find on other sites". */
class FileCopyLookup(
    private val registry: SourceAdapterRegistry,
    private val timeoutMs: Long = LOOKUP_TIMEOUT_MS,
) {
    /** Whether [find] can ask anyone: [post] has a file hash and another available site searches by one. */
    fun canFind(post: Post): Boolean = post.fileHash != null && lookupsFor(post).isNotEmpty()

    /**
     * One post per other site holding [post]'s file, in presentation order. A site that fails or times out is skipped,
     * and so is an answer whose file is not this one (a site that ignores the hash term lists unrelated posts).
     * [onSlow] runs once if the sites have not all answered after [SLOW_NOTICE_MS].
     */
    suspend fun find(post: Post, onSlow: () -> Unit = {}): List<Post> {
        val hash = post.fileHash ?: return emptyList()
        return coroutineScope {
            val notice = launch { delay(SLOW_NOTICE_MS); onSlow() }
            lookupsFor(post).map { lookup ->
                async {
                    runCatchingPreservingCancellation { withTimeoutOrNull(timeoutMs) { lookup.findByFileHash(hash) } }
                        .getOrNull()?.takeIf { it.fileHash == hash }
                }
            }.awaitAll().filterNotNull().also { notice.cancel() }
        }
    }

    private fun lookupsFor(post: Post): List<FileHashLookupSourceAdapter> =
        (registry.availableSources() - post.id.source).inPresentationOrder()
            .mapNotNull { source -> registry.adapterFor(source) as? FileHashLookupSourceAdapter }

    private companion object {
        /** Matches the other single-request source calls (creator pages, related posts). */
        const val LOOKUP_TIMEOUT_MS = 15_000L
        const val SLOW_NOTICE_MS = 1_000L
    }
}
