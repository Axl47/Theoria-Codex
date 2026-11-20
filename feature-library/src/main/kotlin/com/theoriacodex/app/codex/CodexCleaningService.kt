package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.CodexCleaningRequest
import com.theoriacodex.data.repository.CodexCleaningSnapshot
import com.theoriacodex.data.repository.CodexLikesTransactions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps membership comparison and snapshot decoding off the UI thread. */
class CodexCleaningService(
    private val transactions: CodexLikesTransactions,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun clean(request: CodexCleaningRequest): CodexCleaningSnapshot = withContext(dispatcher) {
        transactions.cleanCodex(request)
    }

    suspend fun restore(snapshot: CodexCleaningSnapshot) = withContext(dispatcher) {
        transactions.restoreCleanedCodex(snapshot)
    }
}
