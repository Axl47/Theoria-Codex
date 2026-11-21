package com.theoriacodex.app.codex

import com.theoriacodex.app.codex.transfer.CodexSaveResult
import com.theoriacodex.app.codex.transfer.CodexTransferService
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.flow.first

/** The one save path behind the Save-to-Codex picker and quick save, so both behave the same. */
class CodexSaver(
    private val transfer: CodexTransferService,
    private val codices: CodexRepository,
    private val statistics: StatisticsRepository,
) {
    /** Creates the Codex first when [newCollectionName] is given, saves [posts], and counts a For You save. */
    suspend fun save(
        codexId: String,
        posts: List<Post>,
        cacheFullImage: Boolean,
        fromForYou: Boolean,
        newCollectionName: String? = null,
    ): CodexSaveResult {
        val result = runCatchingPreservingCancellation {
            if (newCollectionName != null) codices.ensureCodex(codexId, newCollectionName)
            transfer.save(codexId, posts, cacheFullImage)
        }.getOrElse { CodexSaveResult.Failure("Could not save posts. Please try again.") }
        if (result is CodexSaveResult.Success && fromForYou) {
            runCatchingPreservingCancellation { statistics.recordForYouSave() }
        }
        return result
    }

    /** The Codex's name, or null when it cannot be read. */
    suspend fun codexName(codexId: String): String? =
        runCatchingPreservingCancellation { codices.observeCodex(codexId).first()?.name }.getOrNull()
}
