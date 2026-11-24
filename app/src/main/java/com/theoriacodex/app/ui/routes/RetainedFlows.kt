package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.ui.findActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal class RetainedFlowsViewModel(private val maxFlows: Int = Int.MAX_VALUE) : ViewModel() {
    private class RetainedFlow(val state: MutableStateFlow<Any?>, val sharing: Job)

    private val flows = LinkedHashMap<String, RetainedFlow>(16, 0.75f, true)

    fun retain(key: String, flow: () -> Flow<Any>): StateFlow<Any?> {
        val retained = flows.getOrPut(key) { share(flow) }
        evictIdleFlows(keeping = key)
        return retained.state
    }

    private fun share(flow: () -> Flow<Any>): RetainedFlow {
        val state = MutableStateFlow<Any?>(null)
        val sharing = viewModelScope.launch {
            SharingStarted.WhileSubscribed(5_000L).command(state.subscriptionCount)
                .distinctUntilChanged()
                .collectLatest { command ->
                    if (command == SharingCommand.START) flow().collect { value -> state.value = value }
                }
        }
        return RetainedFlow(state, sharing)
    }

    private fun evictIdleFlows(keeping: String) {
        val entries = flows.entries.iterator()
        while (flows.size > maxFlows && entries.hasNext()) {
            val (key, retained) = entries.next()
            if (key != keeping && retained.state.subscriptionCount.value == 0) {
                retained.sharing.cancel()
                entries.remove()
            }
        }
    }
}

private const val MAX_CODEX_CONTENT_FLOWS = 12

@Composable
internal fun rememberCodexContentFlows(): RetainedFlowsViewModel = viewModel(
    viewModelStoreOwner = LocalContext.current.findActivity() as? ViewModelStoreOwner
        ?: checkNotNull(LocalViewModelStoreOwner.current),
    key = "codex-content",
) { RetainedFlowsViewModel(MAX_CODEX_CONTENT_FLOWS) }

@Composable
internal fun <T : Any> rememberRetained(
    key: String,
    owner: RetainedFlowsViewModel = viewModel(key = "retained-flows") { RetainedFlowsViewModel() },
    flow: () -> Flow<T>,
): State<T?> {
    @Suppress("UNCHECKED_CAST")
    return remember(owner, key) { owner.retain(key, flow) as StateFlow<T?> }.collectAsStateWithLifecycle()
}
