package com.theoriacodex.app.update

import com.theoriacodex.data.storage.assertStoredFormat
import org.junit.Test

class UpdateStateGoldenTest {
    @Test
    fun `update state files older builds wrote decode the same (ADR 0034)`() {
        val markdown = "## Fixed\n- a=b & c<d> 'quoted' \"double\" é☃"
        val sections = listOf(
            ChangelogSection("Fixed", listOf("a=b & c<d>", "'quoted'")),
            ChangelogSection("", emptyList()),
        )
        val installed = PendingPostInstallChangelog(
            releaseId = 9001L,
            versionCode = 42,
            tagName = "v1.9.0",
            commitShaShort = "dfdcfde",
            changelogMarkdown = markdown,
            changelogSections = sections,
        )
        val pending = installed.copy(fromVersionCode = 41, releaseName = "Tom & Jerry's <release>")
        val current = UpdateStateSnapshot(lastSeenReleaseId = 9001L, lastInstalledChangelog = pending)
        assertStoredFormat(
            golden("kotlinx-full.json") to current,
            golden("full.json") to UpdateStateSnapshot(
                lastSeenReleaseId = 9001L,
                pendingInstallReleaseId = 9002L,
                pendingInstallVersionCode = 43,
                ignoredReleaseId = 8999L,
                remindLaterReleaseId = 9002L,
                remindLaterUntilEpochMs = 1_790_000_000_000L,
                pendingPostInstallChangelog = pending,
                lastInstalledChangelog = installed,
            ),
            golden("sparse-legacy.json") to UpdateStateSnapshot(
                lastSeenReleaseId = 701L,
                ignoredReleaseId = 702L,
                pendingInstallVersionCode = 5,
                pendingPostInstallChangelog = PendingPostInstallChangelog(
                    releaseId = 703L,
                    versionCode = 44,
                    tagName = "v1.0.0",
                    changelogSections = listOf(ChangelogSection("Fixed")),
                ),
            ),
            retiredKeys = setOf("retiredField"),
        )
    }

    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/update-state/$name")) { "missing golden $name" }.readText()
}
