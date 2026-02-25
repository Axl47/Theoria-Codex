package com.theoriacodex.app.update

data class UpdateStateSnapshot(
    val lastSeenReleaseId: Long? = null,
    val pendingInstallReleaseId: Long? = null,
    val pendingInstallVersionCode: Int? = null,
)

interface UpdateStateStore {
    fun snapshot(): UpdateStateSnapshot
    fun setLastSeenReleaseId(releaseId: Long?)
    fun setPendingInstall(releaseId: Long?, versionCode: Int?)
    fun clearPendingInstall()
}
