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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Keeps a navigation entry's last values. The entry outlives its composition while another screen
 * sits on top, so a screen returning draws what it already knows instead of waiting on the database.
 */
internal class RetainedFlowsViewModel(maxFlows: Int = Int.MAX_VALUE) : ViewModel() {
    // Least recently used first; a flow still on screen stays alive through its collector.
    private val flows = object : LinkedHashMap<String, StateFlow<Any?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StateFlow<Any?>>) = size > maxFlows
    }

    fun retain(key: String, flow: () -> Flow<Any>): StateFlow<Any?> = flows.getOrPut(key) {
        flow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)
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
