package com.theoriacodex.app.codex.transfer

import com.google.gson.Gson
import com.theoriacodex.app.codex.buildCodexShareFile
import com.theoriacodex.app.codex.parseCodexShareFile
import com.theoriacodex.app.codex.resolveCodexShareImportPost
import com.theoriacodex.app.codex.sanitizeCodexExportName
import com.theoriacodex.app.codex.selectCodexShareEntries
import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.CodexLikesTransactions
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import com.theoriacodex.domain.model.Post

data class CodexExportPayload(
    val title: String,
    val fileName: String,
    val json: String,
)

sealed interface CodexExportResult {
    data class Success(val payload: CodexExportPayload) : CodexExportResult
    data object NotFound : CodexExportResult
    data object Failure : CodexExportResult
}

sealed interface CodexImportResult {
    data object Unreadable : CodexImportResult
    data object Invalid : CodexImportResult
    data object Failure : CodexImportResult
    data object TimedOut : CodexImportResult
    data class Success(
        val codexId: String,
        val imported: Int,
        val skipped: Int,
    ) : CodexImportResult
}

data class CodexImportProgress(val completed: Int, val total: Int, val committing: Boolean = false)

sealed interface CodexSaveResult {
    data class Success(val inserted: Int, val cacheFailures: Int) : CodexSaveResult
    data class Failure(val message: String) : CodexSaveResult
}

/** Platform-free Codex JSON transfer; ContentResolver/FileProvider remain shell effects. */
class CodexTransferService internal constructor(
    private val codexRepository: CodexRepository,
    private val transactions: CodexLikesTransactions,
    private val cacheRepository: CacheRepository,
    private val sourceRegistry: SourceAdapterRegistry,
    private val gson: Gson = Gson(),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val canonicalizePost: (Post) -> Post = { it },
) {
    suspend fun export(codexId: String): CodexExportResult = withContext(workerDispatcher) {
        runCatchingPreservingCancellation { exportInside(codexId) }.getOrDefault(CodexExportResult.Failure)
    }

    private suspend fun exportInside(codexId: String): CodexExportResult {
        val codex = codexRepository.observeCodex(codexId).first() ?: return CodexExportResult.NotFound
        val posts = codexRepository.observeCodexPosts(codexId, CodexSortMode.NEWEST_SAVED).first()
        return CodexExportResult.Success(
            CodexExportPayload(
                title = codex.name,
                fileName = "${sanitizeCodexExportName(codex.name)}.json",
                json = gson.toJson(buildCodexShareFile(title = codex.name, posts = posts)),
            )
        )
    }

    suspend fun import(
        raw: String?,
        targetCodexId: String,
        onProgress: (CodexImportProgress) -> Unit = {},
    ): CodexImportResult = withContext(workerDispatcher) {
        runCatchingPreservingCancellation { importInside(raw, targetCodexId, onProgress) }.getOrDefault(CodexImportResult.Failure)
    }

    private suspend fun importInside(raw: String?, targetCodexId: String, onProgress: (CodexImportProgress) -> Unit): CodexImportResult {
        if (raw.isNullOrBlank()) return CodexImportResult.Unreadable
        val parsed = parseCodexShareFile(raw) ?: return CodexImportResult.Invalid
        val title = parsed.title?.trim().orEmpty()
        if (title.isBlank()) return CodexImportResult.Invalid

        val entries = selectCodexShareEntries(parsed.posts.orEmpty())
        onProgress(CodexImportProgress(0, entries.size))
        val completed = java.util.concurrent.atomic.AtomicInteger()
        val resolvedPosts = withTimeoutOrNull(120_000L) {
            // A fixed-size batch bounds both live requests and coroutine allocation; awaitAll keeps input order.
            entries.chunked(4).flatMap { batch ->
                coroutineScope {
                    batch.map { (entry, postId) -> async {
                        val resolved = resolveCodexShareImportPost(
                            entry = entry,
                            storedPost = { codexRepository.getPost(postId) },
                            resolveFromSource = {
                                withTimeoutOrNull(15_000L) {
                                    runCatchingPreservingCancellation { sourceRegistry.adapterFor(postId.source)?.resolvePost(postId) }.getOrNull()
                                }
                            },
                        )
                        onProgress(CodexImportProgress(completed.incrementAndGet(), entries.size))
                        resolved?.let(canonicalizePost)
                    } }.awaitAll().filterNotNull()
                }
            }
        } ?: return CodexImportResult.TimedOut
        currentCoroutineContext().ensureActive()
        onProgress(CodexImportProgress(entries.size, entries.size, committing = true))
        val committed = transactions.importCodex(
            codexId = targetCodexId,
            name = title,
            posts = resolvedPosts,
        )
        cacheCommittedPosts(resolvedPosts, cacheFullImage = false)
        return CodexImportResult.Success(
            codexId = committed.codex.codexId,
            imported = committed.acceptedPosts,
            skipped = entries.size - resolvedPosts.size,
        )
    }

    /** Commit the selection atomically; optional cache failures cannot truncate durable saves. */
    suspend fun save(codexId: String, posts: List<Post>, cacheFullImage: Boolean): CodexSaveResult =
        withContext(workerDispatcher) {
            val uniquePosts = posts.map(canonicalizePost).distinctBy(Post::id)
            val committed = runCatchingPreservingCancellation {
                codexRepository.addItems(codexId, uniquePosts)
            }.getOrElse { return@withContext CodexSaveResult.Failure("Could not save posts. Please try again.") }
            CodexSaveResult.Success(committed, cacheCommittedPosts(uniquePosts, cacheFullImage))
        }

    private suspend fun cacheCommittedPosts(posts: List<Post>, cacheFullImage: Boolean): Int =
        posts.count { post ->
            val thumbnail = runCatchingPreservingCancellation { cacheRepository.cacheThumbnail(post) }
            val full = if (cacheFullImage) {
                runCatchingPreservingCancellation { cacheRepository.cacheFull(post) }
            } else Result.success(Unit)
            thumbnail.isFailure || full.isFailure
        }
}
