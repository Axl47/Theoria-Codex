package com.theoriacodex.app.update

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupUpdaterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `eligible update emits awaiting user choice`() = runTest {
        val feed = FakeFeedClient()
        val store = FakeUpdateStateStore()
        var nowMs = 1_000L
        val updater = buildUpdater(
            feedClient = feed,
            stateStore = store,
            nowProvider = { nowMs },
            installedVersionCode = 10,
        )
        val remote = sampleRemote(releaseId = 100, versionCode = 11)
        feed.result = Result.success(remote)

        val states = mutableListOf<StartupUpdateState>()
        val result = updater.checkForEligibleUpdate { state -> states += state }

        assertEquals(remote.releaseId, result.getOrNull()?.releaseId)
        assertTrue(states.first() is StartupUpdateState.Checking)
        assertTrue(states.last() is StartupUpdateState.AwaitingUserChoice)
    }

    @Test
    fun `no skips same release until newer`() = runTest {
        val feed = FakeFeedClient()
        val store = FakeUpdateStateStore()
        val updater = buildUpdater(
            feedClient = feed,
            stateStore = store,
            nowProvider = { 1_000L },
            installedVersionCode = 10,
        )

        val remoteV1 = sampleRemote(releaseId = 101, versionCode = 11)
        feed.result = Result.success(remoteV1)
        updater.onUserChoseNo(remoteV1)

        val sameRelease = updater.checkForEligibleUpdate { }
        assertNull(sameRelease.getOrNull())

        val remoteV2 = sampleRemote(releaseId = 102, versionCode = 12)
        feed.result = Result.success(remoteV2)
        val newerRelease = updater.checkForEligibleUpdate { }
        assertEquals(102L, newerRelease.getOrNull()?.releaseId)
    }

    @Test
    fun `remind later suppresses prompt within window and re-prompts after expiry`() = runTest {
        val feed = FakeFeedClient()
        val store = FakeUpdateStateStore()
        var nowMs = 5_000L
        val updater = buildUpdater(
            feedClient = feed,
            stateStore = store,
            nowProvider = { nowMs },
            installedVersionCode = 10,
        )
        val remote = sampleRemote(releaseId = 103, versionCode = 11)
        feed.result = Result.success(remote)

        updater.onUserChoseRemindLater(remote, nowEpochMs = nowMs)

        val withinWindow = updater.checkForEligibleUpdate { }
        assertNull(withinWindow.getOrNull())

        nowMs += REMIND_LATER_WINDOW_MS + 1L
        val afterExpiry = updater.checkForEligibleUpdate { }
        assertEquals(103L, afterExpiry.getOrNull()?.releaseId)
    }

    @Test
    fun `yes clears matching deferrals`() {
        val feed = FakeFeedClient()
        val store = FakeUpdateStateStore().apply {
            snapshot = snapshot.copy(
                ignoredReleaseId = 201L,
                remindLaterReleaseId = 201L,
                remindLaterUntilEpochMs = 999_999L,
            )
        }
        val updater = buildUpdater(
            feedClient = feed,
            stateStore = store,
            nowProvider = { 1_000L },
            installedVersionCode = 10,
        )

        updater.onUserChoseYes(sampleRemote(releaseId = 201, versionCode = 12))

        val snapshot = store.snapshot()
        assertNull(snapshot.ignoredReleaseId)
        assertNull(snapshot.remindLaterReleaseId)
        assertNull(snapshot.remindLaterUntilEpochMs)
    }

    @Test
    fun `newer release clears stale prompt decisions before prompting`() = runTest {
        val feed = FakeFeedClient()
        val store = FakeUpdateStateStore().apply {
            snapshot = snapshot.copy(
                ignoredReleaseId = 301L,
                remindLaterReleaseId = 301L,
                remindLaterUntilEpochMs = 999_999L,
            )
        }
        val updater = buildUpdater(
            feedClient = feed,
            stateStore = store,
            nowProvider = { 1_000L },
            installedVersionCode = 10,
        )
        val newer = sampleRemote(releaseId = 302L, versionCode = 12)
        feed.result = Result.success(newer)

        val result = updater.checkForEligibleUpdate { }

        assertEquals(newer, result.getOrNull())
        assertNull(store.snapshot().ignoredReleaseId)
        assertNull(store.snapshot().remindLaterReleaseId)
        assertNull(store.snapshot().remindLaterUntilEpochMs)
    }

    @Test
    fun `installed release records last seen and clears pending install`() = runTest {
        val feed = FakeFeedClient()
        val store = FakeUpdateStateStore().apply {
            setPendingInstall(releaseId = 401L, versionCode = 12)
        }
        val updater = buildUpdater(
            feedClient = feed,
            stateStore = store,
            nowProvider = { 1_000L },
            installedVersionCode = 12,
        )
        val installed = sampleRemote(releaseId = 401L, versionCode = 12)
        feed.result = Result.success(installed)
        val states = mutableListOf<StartupUpdateState>()

        val result = updater.checkForEligibleUpdate(states::add)

        assertNull(result.getOrNull())
        assertTrue(states.last() is StartupUpdateState.NoUpdate)
        assertEquals(401L, store.snapshot().lastSeenReleaseId)
        assertNull(store.snapshot().pendingInstallReleaseId)
        assertNull(store.snapshot().pendingInstallVersionCode)
    }

    @Test
    fun `retry clears pending state when downloaded apk is missing`() {
        val context = TempFilesContext(tempFolder.newFolder("missing-apk"))
        val store = FakeUpdateStateStore().apply {
            setPendingInstall(releaseId = 501L, versionCode = 13)
        }
        val updater = buildUpdater(
            feedClient = FakeFeedClient(),
            stateStore = store,
            nowProvider = { 1_000L },
            installedVersionCode = 12,
            context = context,
        )

        val outcome = updater.retryPendingInstall(sampleRemote(501L, 13)) { }

        assertTrue(outcome is StartupUpdateOutcome.ContinueToAppWithError)
        assertNull(store.snapshot().pendingInstallReleaseId)
        assertNull(store.snapshot().pendingInstallVersionCode)
    }

    @Test
    fun `unknown sources retry preserves pending install for a later retry`() {
        val context = TempFilesContext(tempFolder.newFolder("unknown-sources"))
        val store = FakeUpdateStateStore().apply {
            setPendingInstall(releaseId = 601L, versionCode = 14)
        }
        val updater = buildUpdater(
            feedClient = FakeFeedClient(),
            stateStore = store,
            nowProvider = { 1_000L },
            installedVersionCode = 12,
            context = context,
            installer = object : ApkInstaller {
                override fun launchInstaller(apkFile: File): Result<Unit> {
                    return Result.failure(UnknownSourcesPermissionRequiredException())
                }
            },
        )
        val outputFile = ApkDownloadManager(
            context = context,
            outputFileName = "theoria-codex-main.apk",
        ).outputFile()
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(byteArrayOf(1, 2, 3))
        val states = mutableListOf<StartupUpdateState>()

        val outcome = updater.retryPendingInstall(sampleRemote(601L, 14), states::add)

        assertTrue(outcome is StartupUpdateOutcome.AwaitingUnknownSources)
        assertTrue(states.last() is StartupUpdateState.Installing)
        assertEquals(601L, store.snapshot().pendingInstallReleaseId)
        assertEquals(14, store.snapshot().pendingInstallVersionCode)
    }

    private fun buildUpdater(
        feedClient: UpdateFeedClient,
        stateStore: FakeUpdateStateStore,
        nowProvider: () -> Long,
        installedVersionCode: Int,
        context: Context = ContextWrapper(null),
        installer: ApkInstaller = object : ApkInstaller {
            override fun launchInstaller(apkFile: File): Result<Unit> = Result.success(Unit)
        },
    ): StartupUpdater {
        return StartupUpdater(
            context = context,
            feedClient = feedClient,
            downloadManager = ApkDownloadManager(context, outputFileName = "theoria-codex-main.apk"),
            validator = ApkUpdateValidator(context),
            installer = installer,
            stateStore = stateStore,
            updateCheckTimeoutMs = 3_000L,
            installedVersionCodeProvider = { installedVersionCode },
            nowProvider = nowProvider,
        )
    }

    private fun sampleRemote(releaseId: Long, versionCode: Int): RemoteUpdate {
        return RemoteUpdate(
            releaseId = releaseId,
            tagName = "main-vc${versionCode}-abc1234",
            versionCode = versionCode,
            commitShaShort = "abc1234",
            assetDownloadUrl = "https://example.com/theoria-codex-main.apk",
            assetSizeBytes = 123L,
        )
    }

    private class FakeFeedClient : UpdateFeedClient {
        var result: Result<RemoteUpdate?> = Result.success(null)
        var historyResult: Result<List<RemoteUpdate>> = Result.success(emptyList())

        override suspend fun latestMainPrerelease(): Result<RemoteUpdate?> {
            return result
        }

        override suspend fun mainPrereleaseHistory(limit: Int): Result<List<RemoteUpdate>> {
            return historyResult
        }
    }

    private class FakeUpdateStateStore : UpdateStateStore {
        var snapshot = UpdateStateSnapshot()

        override fun snapshot(): UpdateStateSnapshot = snapshot

        override fun setLastSeenReleaseId(releaseId: Long?) {
            snapshot = snapshot.copy(lastSeenReleaseId = releaseId)
        }

        override fun setPendingInstall(releaseId: Long?, versionCode: Int?) {
            snapshot = snapshot.copy(
                pendingInstallReleaseId = releaseId,
                pendingInstallVersionCode = versionCode,
            )
        }

        override fun clearPendingInstall() {
            snapshot = snapshot.copy(
                pendingInstallReleaseId = null,
                pendingInstallVersionCode = null,
            )
        }

        override fun setIgnoredRelease(releaseId: Long?) {
            snapshot = snapshot.copy(ignoredReleaseId = releaseId)
        }

        override fun setRemindLater(releaseId: Long?, untilEpochMs: Long?) {
            snapshot = snapshot.copy(
                remindLaterReleaseId = releaseId,
                remindLaterUntilEpochMs = untilEpochMs,
            )
        }

        override fun clearPromptDeferrals() {
            snapshot = snapshot.copy(
                ignoredReleaseId = null,
                remindLaterReleaseId = null,
                remindLaterUntilEpochMs = null,
            )
        }

        override fun setPendingPostInstallChangelog(changelog: PendingPostInstallChangelog?) {
            snapshot = snapshot.copy(pendingPostInstallChangelog = changelog)
        }

        override fun setLastInstalledChangelog(changelog: PendingPostInstallChangelog?) {
            snapshot = snapshot.copy(lastInstalledChangelog = changelog)
        }
    }

    private class TempFilesContext(
        private val root: File,
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
    }
}
