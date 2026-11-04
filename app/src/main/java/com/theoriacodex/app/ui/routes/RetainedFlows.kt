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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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

/**
 * Keeps a navigation entry's last values. The entry outlives its composition while another screen
 * sits on top, so a screen returning draws what it already knows instead of waiting on the database.
 */
internal class RetainedFlowsViewModel(private val maxFlows: Int = Int.MAX_VALUE) : ViewModel() {
    private class RetainedFlow(val state: MutableStateFlow<Any?>, val sharing: Job)

    // Least recently used first.
    private val flows = LinkedHashMap<String, RetainedFlow>(16, 0.75f, true)

    fun retain(key: String, flow: () -> Flow<Any>): StateFlow<Any?> {
        val retained = flows.getOrPut(key) { share(flow) }
        evictIdleFlows(keeping = key)
        return retained.state
    }

    /** `WhileSubscribed` semantics, with a job and a subscriber count that eviction can see. */
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

    /** Evicting cancels sharing, which would otherwise hold its last value; a flow on screen stays live. */
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

private val retainedFlowsFactory = viewModelFactory { initializer { RetainedFlowsViewModel() } }
private const val MAX_CODEX_CONTENT_FLOWS = 12
private val codexContentFactory = viewModelFactory { initializer { RetainedFlowsViewModel(MAX_CODEX_CONTENT_FLOWS) } }

/**
 * Codex contents kept across navigation entries, about four Codices' worth. Decoding a large Codex
 * outlasts its opening animation, so this lets the Library prepare one before it is tapped.
 */
@Composable
internal fun rememberCodexContentFlows(): RetainedFlowsViewModel = viewModel(
    viewModelStoreOwner = LocalContext.current.findActivity() as? ViewModelStoreOwner
        ?: checkNotNull(LocalViewModelStoreOwner.current),
    key = "codex-content",
    factory = codexContentFactory,
)

/**
 * The latest value of [flow], or null only until it first answers on this navigation entry. [key]
 * must identify everything the flow depends on, because its first flow is kept for the entry's life.
 */
@Composable
internal fun <T : Any> rememberRetained(
    key: String,
    owner: RetainedFlowsViewModel = viewModel(key = "retained-flows", factory = retainedFlowsFactory),
    flow: () -> Flow<T>,
): State<T?> {
    // Each key only ever holds values from the flow that key was given, so this cast is safe.
    @Suppress("UNCHECKED_CAST")
    return remember(owner, key) { owner.retain(key, flow) as StateFlow<T?> }.collectAsStateWithLifecycle()
}
