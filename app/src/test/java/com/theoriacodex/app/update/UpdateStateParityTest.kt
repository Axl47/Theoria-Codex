package com.theoriacodex.app.update

import com.theoriacodex.data.storage.assertGsonParity
import org.junit.Test

class UpdateStateParityTest {
    // full.json is what the Gson store wrote; sparse-legacy.json is an older, partial file with a quoted number and
    // a retired key.
    @Test
    fun `the kotlinx serializer matches Gson on samples and goldens`() {
        val changelog = PendingPostInstallChangelog(
            releaseId = 3L,
            fromVersionCode = 1,
            versionCode = 2,
            tagName = "v1 & <2>",
            commitShaShort = "abc1234",
            releaseName = "It's = done",
            changelogMarkdown = "- fixed",
            changelogSections = listOf(ChangelogSection("Fixed", listOf("one", "two"))),
        )
        assertGsonParity(
            samples = listOf(
                UpdateStateSnapshot(),
                UpdateStateSnapshot(
                    lastSeenReleaseId = 1L,
                    pendingInstallReleaseId = 2L,
                    pendingInstallVersionCode = 3,
                    ignoredReleaseId = 4L,
                    remindLaterReleaseId = 5L,
                    remindLaterUntilEpochMs = 6L,
                    pendingPostInstallChangelog = changelog,
                    lastInstalledChangelog = changelog.copy(releaseId = 9L),
                ),
            ),
            goldenJson = listOf(golden("full.json"), golden("sparse-legacy.json")),
        )
    }

    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/update-state/$name")) { "missing golden $name" }.readText()
}
