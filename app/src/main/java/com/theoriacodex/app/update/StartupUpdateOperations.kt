package com.theoriacodex.app.update

/** Retained shell commands; Android installer and signature checks remain in the updater gateway. */
interface StartupUpdateOperations {
    suspend fun checkForEligibleUpdate(onState: (StartupUpdateState) -> Unit): Result<RemoteUpdate?>
    suspend fun installUpdate(remote: RemoteUpdate, onState: (StartupUpdateState) -> Unit): StartupUpdateOutcome
    suspend fun retryPendingInstall(remote: RemoteUpdate, onState: (StartupUpdateState) -> Unit): StartupUpdateOutcome
    suspend fun onUserChoseYes(remote: RemoteUpdate)
    suspend fun onUserChoseNo(remote: RemoteUpdate)
    suspend fun onUserChoseRemindLater(remote: RemoteUpdate, nowEpochMs: Long? = null)
    suspend fun pendingSnapshot(): UpdateStateSnapshot
    suspend fun dismissPostInstallChangelog()
}
