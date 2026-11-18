package com.theoriacodex.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PendingPostInstallChangelog(
    @SerialName("releaseId")
    val releaseId: Long = 0L,
    @SerialName("fromVersionCode")
    val fromVersionCode: Int? = null,
    @SerialName("versionCode")
    val versionCode: Int = 0,
    @SerialName("tagName")
    val tagName: String = "",
    @SerialName("commitShaShort")
    val commitShaShort: String = "",
    @SerialName("releaseName")
    val releaseName: String? = null,
    @SerialName("changelogMarkdown")
    val changelogMarkdown: String = "",
    @SerialName("changelogSections")
    val changelogSections: List<ChangelogSection> = emptyList(),
)

/** update_state.json, read and written with kotlinx.serialization and held to the Gson-era JSON by UpdateStateParityTest. */
@Serializable
data class UpdateStateSnapshot(
    @SerialName("lastSeenReleaseId")
    val lastSeenReleaseId: Long? = null,
    @SerialName("pendingInstallReleaseId")
    val pendingInstallReleaseId: Long? = null,
    @SerialName("pendingInstallVersionCode")
    val pendingInstallVersionCode: Int? = null,
    @SerialName("ignoredReleaseId")
    val ignoredReleaseId: Long? = null,
    @SerialName("remindLaterReleaseId")
    val remindLaterReleaseId: Long? = null,
    @SerialName("remindLaterUntilEpochMs")
    val remindLaterUntilEpochMs: Long? = null,
    @SerialName("pendingPostInstallChangelog")
    val pendingPostInstallChangelog: PendingPostInstallChangelog? = null,
    @SerialName("lastInstalledChangelog")
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
