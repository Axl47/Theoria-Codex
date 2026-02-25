package com.theoriacodex.app.update

data class UpdateStateSnapshot(
    val lastSeenReleaseId: Long? = null,
    val pendingInstallReleaseId: Long? = null,
    val pendingInstallVersionCode: Int? = null,
    val ignoredReleaseId: Long? = null,
    val remindLaterReleaseId: Long? = null,
    val remindLaterUntilEpochMs: Long? = null,
)

interface UpdateStateStore {
    fun snapshot(): UpdateStateSnapshot
    fun setLastSeenReleaseId(releaseId: Long?)
    fun setPendingInstall(releaseId: Long?, versionCode: Int?)
    fun clearPendingInstall()
    fun setIgnoredRelease(releaseId: Long?)
    fun setRemindLater(releaseId: Long?, untilEpochMs: Long?)
    fun clearPromptDeferrals()
}
