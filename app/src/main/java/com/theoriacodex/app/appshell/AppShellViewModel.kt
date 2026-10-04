package com.theoriacodex.app.appshell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.ui.state.RouteStateOwner
import com.theoriacodex.app.update.StartupUpdateWorkflowEffect
import com.theoriacodex.app.update.StartupUpdateWorkflowEvent
import com.theoriacodex.app.update.StartupUpdateWorkflowReducer
import com.theoriacodex.app.update.StartupUpdateWorkflowState
import com.theoriacodex.app.update.PendingPostInstallChangelog
import com.theoriacodex.app.update.ReleaseChangelogEntry
import com.theoriacodex.app.update.RemoteUpdate
import com.theoriacodex.app.update.StartupUpdateOperations
import com.theoriacodex.app.update.StartupUpdateOutcome
import com.theoriacodex.app.update.UpdateFeedClient
import com.theoriacodex.app.update.mergeReleaseHistory
import com.theoriacodex.app.update.toReleaseHistoryEntry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

internal data class AppShellUiState(
    val startup: StartupUpdateWorkflowState = StartupUpdateWorkflowState(),
    val pendingIncomingUri: PendingIncomingUri? = null,
    val appReady: Boolean = false,
    val readyToEnterApp: Boolean = false,
    val startupReleaseHistory: List<ReleaseChangelogEntry> = emptyList(),
    val latestInstalledChangelog: PendingPostInstallChangelog? = null,
    val postInstallChangelog: PendingPostInstallChangelog? = null,
    val postInstallReleaseHistory: List<ReleaseChangelogEntry> = emptyList(),
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
    scopeOverride: CoroutineScope? = null,
) : ViewModel(), RouteStateOwner<AppShellUiState, AppShellAction, AppShellEffect> {
    private val workScope = scopeOverride ?: viewModelScope
    private var updateEnvironment: UpdateEnvironment? = null
    private var updateJob: Job? = null
    private var startupStarted = false
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
                mutableState.update { it.copy(pendingIncomingUri = pending) }
            }
            is AppShellAction.ConsumeIncomingUri -> {
                incomingUriWorkflow.consume(action.incoming)
                mutableState.update { it.copy(
                    pendingIncomingUri = incomingUriWorkflow.pending.value,
                ) }
            }
            is AppShellAction.UpdateStartup -> {
                var effect: StartupUpdateWorkflowEffect? = null
                mutableState.update { current ->
                    val transition = StartupUpdateWorkflowReducer.reduce(current.startup, action.event)
                    effect = transition.effect
                    current.copy(startup = transition.state,
                        readyToEnterApp = current.readyToEnterApp || transition.effect == StartupUpdateWorkflowEffect.ContinueToApp)
                }
                effect?.takeUnless { it == StartupUpdateWorkflowEffect.ContinueToApp }
                    ?.let { effectChannel.trySend(AppShellEffect.Startup(it)) }
            }
            AppShellAction.MarkAppReady -> {
                savedStateHandle[KEY_APP_READY] = true
                mutableState.update { it.copy(appReady = true) }
            }
        }
    }

    /** Idempotent attachment: recomposition/recreation cannot start a second check or download. */
    fun beginStartup(updater: StartupUpdateOperations, feed: UpdateFeedClient, installedVersion: Int, enabled: Boolean) {
        if (startupStarted) return
        startupStarted = true
        updateEnvironment = UpdateEnvironment(updater, feed, installedVersion)
        launchUpdate {
            if (!enabled || state.value.appReady) {
                event(StartupUpdateWorkflowEvent.ContinuedToApp("Loading app..."))
                if (enabled) loadInstalledChangelog()
                return@launchUpdate
            }
            val remote = updater.checkForEligibleUpdate(::updaterState).getOrThrow()
            if (remote == null) {
                event(StartupUpdateWorkflowEvent.NoEligibleUpdate)
                loadInstalledChangelog()
            } else {
                loadInstalledChangelog()
                val history = mergeReleaseHistory(feed.mainPrereleaseHistory(100).getOrElse { emptyList() },
                    state.value.latestInstalledChangelog).filter { it.versionCode > installedVersion && it.versionCode <= remote.versionCode }
                mutableState.update { it.copy(startupReleaseHistory = history.ifEmpty { listOf(remote.toReleaseHistoryEntry()) }) }
                event(StartupUpdateWorkflowEvent.EligibleUpdateFound(remote))
            }
        }
    }

    fun chooseUpdate(choice: StartupUpdateChoice) {
        val environment = updateEnvironment ?: return
        val remote = state.value.startup.choice ?: return
        if (state.value.startup.actionLocked || updateJob?.isActive == true) return
        event(StartupUpdateWorkflowEvent.InstallStarted)
        launchUpdate {
            when (choice) {
                StartupUpdateChoice.INSTALL -> {
                    environment.updater.onUserChoseYes(remote)
                    finishInstall(environment.updater.installUpdate(remote, ::updaterState))
                }
                StartupUpdateChoice.SKIP -> {
                    environment.updater.onUserChoseNo(remote)
                    event(StartupUpdateWorkflowEvent.ContinuedToApp("Update skipped. Loading app..."))
                }
                StartupUpdateChoice.REMIND_LATER -> {
                    environment.updater.onUserChoseRemindLater(remote)
                    event(StartupUpdateWorkflowEvent.ContinuedToApp("Update postponed for 24 hours. Loading app..."))
                }
            }
            mutableState.update { it.copy(startupReleaseHistory = emptyList()) }
        }
    }

    fun onInstallerReturn(installedVersion: Int) {
        val environment = updateEnvironment ?: return
        val current = state.value.startup
        val remote = current.pendingInstall ?: return
        if (state.value.appReady || updateJob?.isActive == true) return
        launchUpdate {
            if (current.awaitingUnknownSources) {
                val outcome = environment.updater.retryPendingInstall(remote, ::updaterState)
                if (outcome is StartupUpdateOutcome.AwaitingUnknownSources) {
                    failUpdate("Install permission was not granted. Continuing with current app.")
                } else finishInstall(outcome)
            } else if (current.awaitingInstallerReturn) {
                event(if (installedVersion >= remote.versionCode) StartupUpdateWorkflowEvent.ContinuedToApp("Update installed. Loading app...")
                    else StartupUpdateWorkflowEvent.InstallerStillPending)
            }
        }
    }

    fun continueCurrentVersion() {
        if (updateJob?.isActive != true) launchUpdate { failUpdate("Update was not completed. Continuing with current app.") }
    }

    fun dismissPostInstallChangelog() {
        val updater = updateEnvironment?.updater ?: return
        workScope.launch {
            runCatchingPreservingCancellation { updater.dismissPostInstallChangelog() }.onSuccess {
                mutableState.update { it.copy(postInstallChangelog = null, postInstallReleaseHistory = emptyList()) }
            }
        }
    }

    private suspend fun loadInstalledChangelog() {
        val environment = checkNotNull(updateEnvironment)
        val snapshot = environment.updater.pendingSnapshot()
        val pending = snapshot.pendingPostInstallChangelog?.takeIf { it.versionCode <= environment.installedVersion }
        val history = if (pending != null) {
            mergeReleaseHistory(environment.feed.mainPrereleaseHistory(100).getOrElse { emptyList() }, pending)
                .filter { it.versionCode > (pending.fromVersionCode ?: pending.versionCode - 1) && it.versionCode <= environment.installedVersion }
                .ifEmpty { listOf(pending.toReleaseHistoryEntry()) }
        } else emptyList()
        mutableState.update { it.copy(latestInstalledChangelog = pending ?: snapshot.lastInstalledChangelog,
            postInstallChangelog = pending, postInstallReleaseHistory = history) }
    }

    private fun launchUpdate(work: suspend () -> Unit) {
        if (updateJob?.isActive == true) return
        updateJob = workScope.launch(start = CoroutineStart.LAZY) {
            runCatchingPreservingCancellation { work() }.onFailure { failUpdate(it.message ?: "Could not update app") }
        }.also { it.start() }
    }

    private suspend fun finishInstall(outcome: StartupUpdateOutcome) {
        if (outcome is StartupUpdateOutcome.ContinueToAppWithError) failUpdate(outcome.message)
        else event(StartupUpdateWorkflowEvent.InstallFinished(outcome))
    }

    private suspend fun failUpdate(message: String) {
        event(StartupUpdateWorkflowEvent.Failed(message))
        delay(1_200)
        event(StartupUpdateWorkflowEvent.ContinuedToApp())
    }

    private fun updaterState(state: com.theoriacodex.app.update.StartupUpdateState) =
        event(StartupUpdateWorkflowEvent.UpdaterStateChanged(state))
    private fun event(event: StartupUpdateWorkflowEvent) = onAction(AppShellAction.UpdateStartup(event))
    private data class UpdateEnvironment(val updater: StartupUpdateOperations, val feed: UpdateFeedClient, val installedVersion: Int)

    private companion object {
        const val KEY_APP_READY = "app_shell_ready"
    }
}

internal enum class StartupUpdateChoice { INSTALL, SKIP, REMIND_LATER }
