package com.theoriacodex.app.appshell

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.update.RemoteUpdate
import com.theoriacodex.app.update.StartupUpdateOperations
import com.theoriacodex.app.update.StartupUpdateOutcome
import com.theoriacodex.app.update.StartupUpdateState
import com.theoriacodex.app.update.UpdateFeedClient
import com.theoriacodex.app.update.UpdateStateSnapshot
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartupUpdateOwnershipTest {
    @Test fun `repeated UI attachment retains one check and one installation until installer return`() = runTest {
        val gateway = Gateway()
        val owner = AppShellViewModel(SavedStateHandle(), this)
        repeat(2) { owner.beginStartup(gateway, gateway, 10, true) }
        runCurrent()
        assertEquals(1, gateway.checks)
        gateway.checked.complete(Unit)
        runCurrent()
        assertEquals(gateway.remote, owner.state.value.startup.choice)
        owner.chooseUpdate(StartupUpdateChoice.INSTALL)
        owner.chooseUpdate(StartupUpdateChoice.INSTALL)
        runCurrent()
        repeat(2) { owner.beginStartup(gateway, gateway, 10, true) }
        assertEquals(1, gateway.installs)
        assertTrue(owner.state.value.startup.actionLocked)
        gateway.installed.complete(Unit)
        runCurrent()
        assertTrue(owner.state.value.startup.awaitingInstallerReturn)
        // A new UI observer receives the retained state even when no collector existed during download.
        assertTrue(owner.state.first().startup.awaitingInstallerReturn)
        owner.onInstallerReturn(11)
        runCurrent()
        assertTrue(owner.state.value.readyToEnterApp)
        assertEquals(1, gateway.checks)
        assertEquals(1, gateway.installs)
    }

    @Test fun `skip and postpone decisions commit before durable startup continuation`() = runTest {
        for (choice in listOf(StartupUpdateChoice.SKIP, StartupUpdateChoice.REMIND_LATER)) {
            val gateway = Gateway().apply { checked.complete(Unit) }
            val owner = AppShellViewModel(SavedStateHandle(), this)
            owner.beginStartup(gateway, gateway, 10, true)
            runCurrent()
            owner.chooseUpdate(choice)
            assertFalse(owner.state.value.readyToEnterApp)
            runCurrent()
            assertEquals(listOf(choice), gateway.choices)
            assertTrue(owner.state.value.readyToEnterApp)
            assertEquals(0, gateway.installs)
        }
    }

    @Test fun `updater exceptions become visible failure then allow current app without duplicate work`() = runTest {
        val gateway = Gateway().apply { checked.complete(Unit); failure = true }
        val owner = AppShellViewModel(SavedStateHandle(), this)
        owner.beginStartup(gateway, gateway, 10, true)
        runCurrent()
        assertTrue(owner.state.value.startup.updateState is StartupUpdateState.Failed)
        owner.beginStartup(gateway, gateway, 10, true)
        advanceUntilIdle()
        assertTrue(owner.state.value.readyToEnterApp)
        assertEquals(1, gateway.checks)
    }

    private class Gateway : StartupUpdateOperations, UpdateFeedClient {
        val checked = CompletableDeferred<Unit>()
        val installed = CompletableDeferred<Unit>()
        val choices = mutableListOf<StartupUpdateChoice>()
        val remote = RemoteUpdate(1, "v0.1.1", 11, "abcdef0", "https://example.test/app.apk", 10)
        var checks = 0
        var installs = 0
        var failure = false
        override suspend fun checkForEligibleUpdate(onState: (StartupUpdateState) -> Unit): Result<RemoteUpdate?> {
            checks++
            checked.await()
            if (failure) error("controlled failure")
            return Result.success(remote)
        }
        override suspend fun installUpdate(remote: RemoteUpdate, onState: (StartupUpdateState) -> Unit): StartupUpdateOutcome {
            installs++
            onState(StartupUpdateState.Downloading(0.5f))
            installed.await()
            return StartupUpdateOutcome.InstallerLaunched(remote, File("app.apk"))
        }
        override suspend fun retryPendingInstall(remote: RemoteUpdate, onState: (StartupUpdateState) -> Unit) =
            StartupUpdateOutcome.InstallerLaunched(remote, File("app.apk"))
        override suspend fun onUserChoseYes(remote: RemoteUpdate) { choices += StartupUpdateChoice.INSTALL }
        override suspend fun onUserChoseNo(remote: RemoteUpdate) { choices += StartupUpdateChoice.SKIP }
        override suspend fun onUserChoseRemindLater(remote: RemoteUpdate, nowEpochMs: Long?) { choices += StartupUpdateChoice.REMIND_LATER }
        override suspend fun pendingSnapshot() = UpdateStateSnapshot()
        override suspend fun dismissPostInstallChangelog() = Unit
        override suspend fun latestMainPrerelease(): Result<RemoteUpdate?> = Result.success(remote)
        override suspend fun mainPrereleaseHistory(limit: Int) = Result.success(listOf(remote))
    }
}
