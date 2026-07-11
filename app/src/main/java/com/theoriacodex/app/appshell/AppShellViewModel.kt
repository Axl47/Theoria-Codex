package com.theoriacodex.app.appshell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.theoriacodex.app.ui.state.RouteStateOwner
import com.theoriacodex.app.update.StartupUpdateWorkflowEffect
import com.theoriacodex.app.update.StartupUpdateWorkflowEvent
import com.theoriacodex.app.update.StartupUpdateWorkflowReducer
import com.theoriacodex.app.update.StartupUpdateWorkflowState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

internal data class AppShellUiState(
    val startup: StartupUpdateWorkflowState = StartupUpdateWorkflowState(),
    val pendingIncomingUri: PendingIncomingUri? = null,
    val appReady: Boolean = false,
)

internal sealed interface AppShellAction {
    data class AcceptIncomingUri(
        val uri: String,
        val isPixivAuthorizationCallback: Boolean,
        val isCodexImport: Boolean,
    ) : AppShellAction

    data class ConsumeIncomingUri(val incoming: PendingIncomingUri) : AppShellAction
    data class UpdateStartup(val event: StartupUpdateWorkflowEvent) : AppShellAction
    data object MarkAppReady : AppShellAction
}

internal sealed interface AppShellEffect {
    data class Startup(val effect: StartupUpdateWorkflowEffect) : AppShellEffect
}

/** Activity-scoped owner for system workflows; navigation and Android launchers remain in Compose. */
internal class AppShellViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel(), RouteStateOwner<AppShellUiState, AppShellAction, AppShellEffect> {
    private val incomingUriWorkflow = IncomingUriWorkflow(savedStateHandle)
    private val mutableState = MutableStateFlow(
        AppShellUiState(
            pendingIncomingUri = incomingUriWorkflow.pending.value,
            appReady = savedStateHandle[KEY_APP_READY] ?: false,
        )
    )
    private val effectChannel = Channel<AppShellEffect>(capacity = Channel.BUFFERED)

    override val state: StateFlow<AppShellUiState> = mutableState.asStateFlow()
    override val effects: Flow<AppShellEffect> = effectChannel.receiveAsFlow()

    override fun onAction(action: AppShellAction) {
        when (action) {
            is AppShellAction.AcceptIncomingUri -> {
                val pending = incomingUriWorkflow.accept(
                    uri = action.uri,
                    isPixivAuthorizationCallback = action.isPixivAuthorizationCallback,
                    isCodexImport = action.isCodexImport,
                )
                mutableState.value = mutableState.value.copy(pendingIncomingUri = pending)
            }
            is AppShellAction.ConsumeIncomingUri -> {
                incomingUriWorkflow.consume(action.incoming)
                mutableState.value = mutableState.value.copy(
                    pendingIncomingUri = incomingUriWorkflow.pending.value,
                )
            }
            is AppShellAction.UpdateStartup -> {
                val transition = StartupUpdateWorkflowReducer.reduce(
                    current = mutableState.value.startup,
                    event = action.event,
                )
                mutableState.value = mutableState.value.copy(startup = transition.state)
                transition.effect?.let { effect -> effectChannel.trySend(AppShellEffect.Startup(effect)) }
            }
            AppShellAction.MarkAppReady -> {
                savedStateHandle[KEY_APP_READY] = true
                mutableState.value = mutableState.value.copy(appReady = true)
            }
        }
    }

    private companion object {
        const val KEY_APP_READY = "app_shell_ready"
    }
}
