package com.theoriacodex.app.update

data class PendingPostInstallChangelog(
    val releaseId: Long,
    val fromVersionCode: Int? = null,
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
    suspend fun snapshot(): UpdateStateSnapshot
    suspend fun update(transform: (UpdateStateSnapshot) -> UpdateStateSnapshot)

    suspend fun setLastSeenReleaseId(releaseId: Long?) = update { current ->
        current.copy(lastSeenReleaseId = releaseId)
    }

    suspend fun setPendingInstall(releaseId: Long?, versionCode: Int?) = update { current ->
        current.copy(
            pendingInstallReleaseId = releaseId,
            pendingInstallVersionCode = versionCode,
        )
    }

    suspend fun clearPendingInstall() = update { current ->
        current.copy(
            pendingInstallReleaseId = null,
            pendingInstallVersionCode = null,
        )
    }

    suspend fun setIgnoredRelease(releaseId: Long?) = update { current ->
        current.copy(ignoredReleaseId = releaseId)
    }

    suspend fun setRemindLater(releaseId: Long?, untilEpochMs: Long?) = update { current ->
        current.copy(
            remindLaterReleaseId = releaseId,
            remindLaterUntilEpochMs = untilEpochMs,
        )
    }

    suspend fun clearPromptDeferrals() = update { current ->
        current.copy(
            ignoredReleaseId = null,
            remindLaterReleaseId = null,
            remindLaterUntilEpochMs = null,
        )
    }

    suspend fun setPendingPostInstallChangelog(changelog: PendingPostInstallChangelog?) = update { current ->
        current.copy(pendingPostInstallChangelog = changelog)
    }

    suspend fun setLastInstalledChangelog(changelog: PendingPostInstallChangelog?) = update { current ->
        current.copy(lastInstalledChangelog = changelog)
    }
}
