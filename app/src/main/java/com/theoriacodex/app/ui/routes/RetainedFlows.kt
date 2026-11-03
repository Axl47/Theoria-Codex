package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Keeps a navigation entry's last values. The entry outlives its composition while another screen
 * sits on top, so a screen returning draws what it already knows instead of waiting on the database.
 */
internal class RetainedFlowsViewModel : ViewModel() {
    private val flows = mutableMapOf<String, StateFlow<Any?>>()

    fun retain(key: String, flow: () -> Flow<Any>): StateFlow<Any?> = flows.getOrPut(key) {
        flow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)
    }
}

private val retainedFlowsFactory = viewModelFactory { initializer { RetainedFlowsViewModel() } }

/**
 * The latest value of [flow], or null only until it first answers on this navigation entry. [key]
 * must identify everything the flow depends on, because its first flow is kept for the entry's life.
 */
@Composable
internal fun <T : Any> rememberRetained(key: String, flow: () -> Flow<T>): State<T?> {
    val owner = viewModel<RetainedFlowsViewModel>(key = "retained-flows", factory = retainedFlowsFactory)
    // Each key only ever holds values from the flow that key was given, so this cast is safe.
    @Suppress("UNCHECKED_CAST")
    return remember(owner, key) { owner.retain(key, flow) as StateFlow<T?> }.collectAsStateWithLifecycle()
}
