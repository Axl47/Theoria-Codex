package com.theoriacodex.app.update

data class PendingPostInstallChangelog(
    val releaseId: Long,
    val versionCode: Int,
    val tagName: String,
    val commitShaShort: String,
    val releaseName: String? = null,
    val changelogMarkdown: String = "",
    val changelogSections: List<ChangelogSection> = emptyList(),
)

data class UpdateStateSnapshot(
    val lastSeenReleaseId: Long? = null,
    val pendingInstallReleaseId: Long? = null,
    val pendingInstallVersionCode: Int? = null,
    val ignoredReleaseId: Long? = null,
    val remindLaterReleaseId: Long? = null,
    val remindLaterUntilEpochMs: Long? = null,
    val pendingPostInstallChangelog: PendingPostInstallChangelog? = null,
    val lastInstalledChangelog: PendingPostInstallChangelog? = null,
)

interface UpdateStateStore {
    fun snapshot(): UpdateStateSnapshot
    fun setLastSeenReleaseId(releaseId: Long?)
    fun setPendingInstall(releaseId: Long?, versionCode: Int?)
    fun clearPendingInstall()
    fun setIgnoredRelease(releaseId: Long?)
    fun setRemindLater(releaseId: Long?, untilEpochMs: Long?)
    fun clearPromptDeferrals()
    fun setPendingPostInstallChangelog(changelog: PendingPostInstallChangelog?)
    fun setLastInstalledChangelog(changelog: PendingPostInstallChangelog?)
}
