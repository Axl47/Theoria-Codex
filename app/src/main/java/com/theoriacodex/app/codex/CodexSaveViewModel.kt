package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.codex.transfer.CodexSaveResult
import com.theoriacodex.app.codex.transfer.CodexTransferService
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Activity-owned save jobs survive sheet dismissal and configuration changes. */
internal class CodexSaveViewModel(
    private val transfer: CodexTransferService,
    private val codices: CodexRepository,
    private val statistics: StatisticsRepository,
) : ViewModel() {
    private val messages = Channel<String>(Channel.BUFFERED)
    val effects = messages.receiveAsFlow()

    fun save(
        codexId: String,
        posts: List<Post>,
        cacheFullImage: Boolean,
        fromForYou: Boolean,
        newCollectionName: String? = null,
    ) {
        viewModelScope.launch {
            val result = runCatchingPreservingCancellation {
                if (newCollectionName != null) codices.ensureCodex(codexId, newCollectionName)
                transfer.save(codexId, posts, cacheFullImage)
            }.getOrElse { CodexSaveResult.Failure("Could not save posts. Please try again.") }
            if (result is CodexSaveResult.Success && fromForYou) {
                runCatchingPreservingCancellation { statistics.recordForYouSave() }
            }
            messages.send(
                when (result) {
                    is CodexSaveResult.Failure -> result.message
                    is CodexSaveResult.Success -> when {
                        result.cacheFailures > 0 -> "Posts saved. Some offline copies could not be cached."
                        result.inserted == 0 -> "Posts are already in this Codex."
                        result.inserted == 1 -> "Post saved to Codex."
                        else -> "${result.inserted} posts saved to Codex."
                    }
                },
            )
        }
    }
}
