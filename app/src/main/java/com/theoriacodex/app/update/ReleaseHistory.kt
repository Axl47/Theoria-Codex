package com.theoriacodex.app.update

internal data class ReleaseChangelogEntry(
    val releaseId: Long?,
    val versionCode: Int,
    val commitShaShort: String,
    val releaseName: String?,
    val publishedAtEpochMs: Long?,
    val changelogMarkdown: String,
    val changelogSections: List<ChangelogSection>,
)

internal fun mergeReleaseHistory(
    remoteHistory: List<RemoteUpdate>,
    localCurrent: PendingPostInstallChangelog?,
): List<ReleaseChangelogEntry> {
    val entries = remoteHistory.map(RemoteUpdate::toReleaseHistoryEntry).toMutableList()
    localCurrent?.let { local ->
        val alreadyPresent = entries.any { entry ->
            entry.releaseId == local.releaseId ||
                (entry.versionCode == local.versionCode && entry.commitShaShort == local.commitShaShort)
        }
        if (!alreadyPresent) entries += local.toReleaseHistoryEntry()
    }
    return entries.sortedWith(releaseChangelogNewestFirstComparator())
}

internal fun PendingPostInstallChangelog.toReleaseHistoryEntry() = ReleaseChangelogEntry(
    releaseId = releaseId,
    versionCode = versionCode,
    commitShaShort = commitShaShort,
    releaseName = releaseName,
    publishedAtEpochMs = null,
    changelogMarkdown = changelogMarkdown,
    changelogSections = changelogSections,
)

internal fun RemoteUpdate.toReleaseHistoryEntry() = ReleaseChangelogEntry(
    releaseId = releaseId,
    versionCode = versionCode,
    commitShaShort = commitShaShort,
    releaseName = releaseName,
    publishedAtEpochMs = publishedAtEpochMs,
    changelogMarkdown = changelogMarkdown,
    changelogSections = changelogSections,
)

internal fun releaseChangelogNewestFirstComparator(): Comparator<ReleaseChangelogEntry> {
    return compareByDescending<ReleaseChangelogEntry> { it.publishedAtEpochMs ?: Long.MIN_VALUE }
        .thenByDescending(ReleaseChangelogEntry::versionCode)
}

