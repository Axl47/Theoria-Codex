package com.theoriacodex.app.source

import com.theoriacodex.domain.adapter.FileHashLookupSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.fileHash
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class FileCopyLookup(
    private val registry: SourceAdapterRegistry,
    private val knownCopies: (Post) -> Set<PostId> = { setOf(it.id) },
    private val timeoutMs: Long = LOOKUP_TIMEOUT_MS,
) {
    fun canFind(post: Post): Boolean = knownCopiesOf(post).isNotEmpty() || (post.fileHash != null && lookupsFor(post).isNotEmpty())

    suspend fun find(post: Post, onSlow: () -> Unit = {}): List<Post> = coroutineScope {
        val notice = launch { delay(SLOW_NOTICE_MS); onSlow() }
        val known = knownCopiesOf(post).map { id -> async { ask { registry.adapterFor(id.source)?.resolvePost(id) } } }
        val hash = post.fileHash
        val byFile = if (hash == null) emptyList() else lookupsFor(post).map { lookup ->
            async { ask { lookup.findByFileHash(hash) }?.takeIf { it.fileHash == hash } }
        }
        (known + byFile).awaitAll().filterNotNull().distinctBy(Post::id).filter { it.id != post.id }.also { notice.cancel() }
    }

    private suspend fun ask(call: suspend () -> Post?): Post? =
        runCatchingPreservingCancellation { withTimeoutOrNull(timeoutMs) { call() } }.getOrNull()

    private fun knownCopiesOf(post: Post): List<PostId> =
        (knownCopies(post) - post.id).filter { registry.adapterFor(it.source) != null && it.source in registry.availableSources() }

    private fun lookupsFor(post: Post): List<FileHashLookupSourceAdapter> =
        (registry.availableSources() - post.id.source).inPresentationOrder()
            .mapNotNull { source -> registry.adapterFor(source) as? FileHashLookupSourceAdapter }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 15_000L
        const val SLOW_NOTICE_MS = 1_000L
    }
}
