package com.theoriacodex.app.update

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PendingPostInstallChangelog(
    @field:SerializedName("releaseId")
    @SerialName("releaseId")
    val releaseId: Long = 0L,
    @field:SerializedName("fromVersionCode")
    @SerialName("fromVersionCode")
    val fromVersionCode: Int? = null,
    @field:SerializedName("versionCode")
    @SerialName("versionCode")
    val versionCode: Int = 0,
    @field:SerializedName("tagName")
    @SerialName("tagName")
    val tagName: String = "",
    @field:SerializedName("commitShaShort")
    @SerialName("commitShaShort")
    val commitShaShort: String = "",
    @field:SerializedName("releaseName")
    @SerialName("releaseName")
    val releaseName: String? = null,
    @field:SerializedName("changelogMarkdown")
    @SerialName("changelogMarkdown")
    val changelogMarkdown: String = "",
    @field:SerializedName("changelogSections")
    @SerialName("changelogSections")
    val changelogSections: List<ChangelogSection> = emptyList(),
)

/**
 * update_state.json. Gson still reads and writes it; the kotlinx serializer is the migration prototype, held to the
 * same JSON by UpdateStateParityTest and the minified acceptance run. Keep both names on each property in sync.
 */
@Serializable
data class UpdateStateSnapshot(
    @field:SerializedName("lastSeenReleaseId")
    @SerialName("lastSeenReleaseId")
    val lastSeenReleaseId: Long? = null,
    @field:SerializedName("pendingInstallReleaseId")
    @SerialName("pendingInstallReleaseId")
    val pendingInstallReleaseId: Long? = null,
    @field:SerializedName("pendingInstallVersionCode")
    @SerialName("pendingInstallVersionCode")
    val pendingInstallVersionCode: Int? = null,
    @field:SerializedName("ignoredReleaseId")
    @SerialName("ignoredReleaseId")
    val ignoredReleaseId: Long? = null,
    @field:SerializedName("remindLaterReleaseId")
    @SerialName("remindLaterReleaseId")
    val remindLaterReleaseId: Long? = null,
    @field:SerializedName("remindLaterUntilEpochMs")
    @SerialName("remindLaterUntilEpochMs")
    val remindLaterUntilEpochMs: Long? = null,
    @field:SerializedName("pendingPostInstallChangelog")
    @SerialName("pendingPostInstallChangelog")
    val pendingPostInstallChangelog: PendingPostInstallChangelog? = null,
    @field:SerializedName("lastInstalledChangelog")
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
