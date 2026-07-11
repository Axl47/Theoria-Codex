package com.theoriacodex.app.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupUpdateWorkflowTest {
    @Test
    fun `eligible update becomes the sole prompt choice`() {
        val remote = remoteUpdate()

        val transition = StartupUpdateWorkflowReducer.reduce(
            StartupUpdateWorkflowState(),
            StartupUpdateWorkflowEvent.EligibleUpdateFound(remote),
        )

        assertEquals(remote, transition.state.choice)
        assertEquals(StartupUpdateState.AwaitingUserChoice(remote), transition.state.updateState)
        assertFalse(transition.state.actionLocked)
        assertNull(transition.effect)
    }

    @Test
    fun `unknown sources outcome locks workflow and returns Android effect`() {
        val remote = remoteUpdate()
        val apk = File("update.apk")
        val current = StartupUpdateWorkflowState(choice = remote, actionLocked = true)

        val transition = StartupUpdateWorkflowReducer.reduce(
            current,
            StartupUpdateWorkflowEvent.InstallFinished(
                StartupUpdateOutcome.AwaitingUnknownSources(remote, apk)
            ),
        )

        assertTrue(transition.state.awaitingUnknownSources)
        assertFalse(transition.state.awaitingInstallerReturn)
        assertEquals(remote, transition.state.pendingInstall)
        assertEquals(StartupUpdateWorkflowEffect.OpenUnknownSourcesSettings, transition.effect)
    }

    @Test
    fun `installer launch and failure produce one shot shell effects`() {
        val remote = remoteUpdate()
        val apk = File("update.apk")
        val launched = StartupUpdateWorkflowReducer.reduce(
            StartupUpdateWorkflowState(choice = remote, actionLocked = true),
            StartupUpdateWorkflowEvent.InstallFinished(
                StartupUpdateOutcome.InstallerLaunched(remote, apk)
            ),
        )
        assertTrue(launched.state.awaitingInstallerReturn)
        assertEquals(StartupUpdateWorkflowEffect.InstallerOpened(remote, apk), launched.effect)

        val failed = StartupUpdateWorkflowReducer.reduce(
            launched.state,
            StartupUpdateWorkflowEvent.Failed("network failed"),
        )
        assertEquals(StartupUpdateState.Failed("network failed"), failed.state.updateState)
        assertNull(failed.effect)
        assertFalse(failed.state.actionLocked)
    }

    private fun remoteUpdate(): RemoteUpdate {
        return RemoteUpdate(
            releaseId = 42,
            tagName = "v1.2.3",
            versionCode = 1_500_010_203,
            commitShaShort = "abcdef0",
            assetDownloadUrl = "https://example.test/update.apk",
            assetSizeBytes = 100,
        )
    }
}
