package com.theoriacodex.app.update

import java.io.File

internal data class StartupUpdateWorkflowState(
    val updateState: StartupUpdateState = StartupUpdateState.Checking,
    val statusMessage: String = StartupUpdateState.Checking.messageText(),
    val choice: RemoteUpdate? = null,
    val pendingInstall: RemoteUpdate? = null,
    val awaitingUnknownSources: Boolean = false,
    val awaitingInstallerReturn: Boolean = false,
    val actionLocked: Boolean = false,
)

internal sealed interface StartupUpdateWorkflowEvent {
    data class UpdaterStateChanged(val state: StartupUpdateState) : StartupUpdateWorkflowEvent
    data class EligibleUpdateFound(val remote: RemoteUpdate) : StartupUpdateWorkflowEvent
    data object NoEligibleUpdate : StartupUpdateWorkflowEvent
    data object InstallStarted : StartupUpdateWorkflowEvent
    data class InstallFinished(val outcome: StartupUpdateOutcome) : StartupUpdateWorkflowEvent
    data class Failed(val message: String) : StartupUpdateWorkflowEvent
    data class ContinuedToApp(val statusMessage: String = "Ready") : StartupUpdateWorkflowEvent
    data object InstallerStillPending : StartupUpdateWorkflowEvent
}

internal sealed interface StartupUpdateWorkflowEffect {
    data object ContinueToApp : StartupUpdateWorkflowEffect
    data object OpenUnknownSourcesSettings : StartupUpdateWorkflowEffect
    data class InstallerOpened(val remote: RemoteUpdate, val apkFile: File) : StartupUpdateWorkflowEffect
}

internal data class StartupUpdateWorkflowTransition(
    val state: StartupUpdateWorkflowState,
    val effect: StartupUpdateWorkflowEffect? = null,
)

/** Pure transition boundary; Android launchers remain hosted by the app shell. */
internal object StartupUpdateWorkflowReducer {
    fun reduce(
        current: StartupUpdateWorkflowState,
        event: StartupUpdateWorkflowEvent,
    ): StartupUpdateWorkflowTransition {
        return when (event) {
            is StartupUpdateWorkflowEvent.UpdaterStateChanged -> StartupUpdateWorkflowTransition(
                current.copy(
                    updateState = event.state,
                    statusMessage = event.state.messageText(),
                )
            )
            is StartupUpdateWorkflowEvent.EligibleUpdateFound -> StartupUpdateWorkflowTransition(
                current.copy(
                    updateState = StartupUpdateState.AwaitingUserChoice(event.remote),
                    statusMessage = StartupUpdateState.AwaitingUserChoice(event.remote).messageText(),
                    choice = event.remote,
                    actionLocked = false,
                )
            )
            StartupUpdateWorkflowEvent.NoEligibleUpdate -> StartupUpdateWorkflowTransition(
                current.copy(
                    updateState = StartupUpdateState.NoUpdate,
                    statusMessage = StartupUpdateState.NoUpdate.messageText(),
                    choice = null,
                    actionLocked = false,
                ),
                StartupUpdateWorkflowEffect.ContinueToApp,
            )
            StartupUpdateWorkflowEvent.InstallStarted -> StartupUpdateWorkflowTransition(
                current.copy(actionLocked = true)
            )
            is StartupUpdateWorkflowEvent.InstallFinished -> reduceInstallOutcome(current, event.outcome)
            is StartupUpdateWorkflowEvent.Failed -> StartupUpdateWorkflowTransition(
                current.copy(
                    updateState = StartupUpdateState.Failed(event.message),
                    statusMessage = event.message,
                    choice = null,
                    pendingInstall = null,
                    awaitingUnknownSources = false,
                    awaitingInstallerReturn = false,
                    actionLocked = false,
                ),
            )
            is StartupUpdateWorkflowEvent.ContinuedToApp -> StartupUpdateWorkflowTransition(
                current.copy(
                    updateState = StartupUpdateState.NoUpdate,
                    statusMessage = event.statusMessage,
                    choice = null,
                    pendingInstall = null,
                    awaitingUnknownSources = false,
                    awaitingInstallerReturn = false,
                    actionLocked = false,
                ),
                StartupUpdateWorkflowEffect.ContinueToApp,
            )
            StartupUpdateWorkflowEvent.InstallerStillPending -> StartupUpdateWorkflowTransition(
                current.copy(
                    awaitingInstallerReturn = true,
                    actionLocked = true,
                    statusMessage = "Installer opened. Complete update to continue, or continue without updating.",
                )
            )
        }
    }

    private fun reduceInstallOutcome(
        current: StartupUpdateWorkflowState,
        outcome: StartupUpdateOutcome,
    ): StartupUpdateWorkflowTransition {
        return when (outcome) {
            StartupUpdateOutcome.ContinueToApp -> reduce(
                current,
                StartupUpdateWorkflowEvent.ContinuedToApp(),
            )
            is StartupUpdateOutcome.ContinueToAppWithError -> reduce(
                current,
                StartupUpdateWorkflowEvent.Failed(outcome.message),
            )
            is StartupUpdateOutcome.AwaitingUnknownSources -> StartupUpdateWorkflowTransition(
                current.copy(
                    choice = null,
                    pendingInstall = outcome.remote,
                    awaitingUnknownSources = true,
                    awaitingInstallerReturn = false,
                    actionLocked = true,
                    statusMessage = "Grant install permission to continue update...",
                ),
                StartupUpdateWorkflowEffect.OpenUnknownSourcesSettings,
            )
            is StartupUpdateOutcome.InstallerLaunched -> StartupUpdateWorkflowTransition(
                current.copy(
                    choice = null,
                    pendingInstall = outcome.remote,
                    awaitingUnknownSources = false,
                    awaitingInstallerReturn = true,
                    actionLocked = true,
                    statusMessage = "Installer opened. Complete update to reload app.",
                ),
                StartupUpdateWorkflowEffect.InstallerOpened(outcome.remote, outcome.apkFile),
            )
        }
    }
}
