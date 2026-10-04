package com.theoriacodex.app.codex.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CodexImportState(val running: Boolean = false, val progress: CodexImportProgress? = null)

/** One retained import owns document reading, resolution and the atomic commit across UI reattachment. */
internal class CodexImportViewModel(
    private val transfer: CodexTransferService,
    private val readDocument: suspend (String) -> String?,
    private val newTargetCodexId: suspend () -> String,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutableState = MutableStateFlow(CodexImportState())
    val state = mutableState.asStateFlow()
    private val messages = Channel<CodexImportResult>(Channel.BUFFERED)
    val effects = messages.receiveAsFlow()
    private var job: Job? = null
    private var lastRequest: Pair<String, String>? = null

    fun start(document: String) {
        if (state.value.running) return
        launchImport(document, null)
    }

    fun retry() {
        if (state.value.running) return
        lastRequest?.let { (document, target) -> launchImport(document, target) }
    }

    fun cancel() {
        if (state.value.progress?.committing != true) job?.cancel()
    }

    private fun launchImport(document: String, retainedTarget: String?) {
        mutableState.value = CodexImportState(running = true)
        job = scope.launch {
            try {
                val target = retainedTarget ?: newTargetCodexId()
                lastRequest = document to target
                val raw = readDocument(document)
                val result = transfer.import(raw, target) { progress ->
                    mutableState.update { current ->
                        current.copy(progress = progress.copy(completed = maxOf(progress.completed, current.progress?.completed ?: 0)))
                    }
                }
                messages.send(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                messages.send(CodexImportResult.Invalid)
            } catch (_: Exception) {
                messages.send(CodexImportResult.Unreadable)
            } finally {
                mutableState.value = CodexImportState()
            }
        }
    }
}
