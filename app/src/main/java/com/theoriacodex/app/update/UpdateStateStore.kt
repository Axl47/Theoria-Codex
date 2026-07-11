package com.theoriacodex.app.update

import com.google.gson.annotations.SerializedName

data class PendingPostInstallChangelog(
    @field:SerializedName("releaseId")
    val releaseId: Long = 0L,
    @field:SerializedName("fromVersionCode")
    val fromVersionCode: Int? = null,
    @field:SerializedName("versionCode")
    val versionCode: Int = 0,
    @field:SerializedName("tagName")
    val tagName: String = "",
    @field:SerializedName("commitShaShort")
    val commitShaShort: String = "",
    @field:SerializedName("releaseName")
    val releaseName: String? = null,
    @field:SerializedName("changelogMarkdown")
    val changelogMarkdown: String = "",
    @field:SerializedName("changelogSections")
    val changelogSections: List<ChangelogSection> = emptyList(),
)

data class UpdateStateSnapshot(
    @field:SerializedName("lastSeenReleaseId")
    val lastSeenReleaseId: Long? = null,
    @field:SerializedName("pendingInstallReleaseId")
    val pendingInstallReleaseId: Long? = null,
    @field:SerializedName("pendingInstallVersionCode")
    val pendingInstallVersionCode: Int? = null,
    @field:SerializedName("ignoredReleaseId")
    val ignoredReleaseId: Long? = null,
    @field:SerializedName("remindLaterReleaseId")
    val remindLaterReleaseId: Long? = null,
    @field:SerializedName("remindLaterUntilEpochMs")
    val remindLaterUntilEpochMs: Long? = null,
    @field:SerializedName("pendingPostInstallChangelog")
    val pendingPostInstallChangelog: PendingPostInstallChangelog? = null,
    @field:SerializedName("lastInstalledChangelog")
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
