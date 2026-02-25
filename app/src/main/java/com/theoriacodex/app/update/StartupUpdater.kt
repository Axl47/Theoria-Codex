package com.theoriacodex.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.withTimeoutOrNull

internal const val REMIND_LATER_WINDOW_MS: Long = 24L * 60L * 60L * 1000L

sealed interface StartupUpdateOutcome {
    data object ContinueToApp : StartupUpdateOutcome
    data class ContinueToAppWithError(val message: String) : StartupUpdateOutcome
    data class AwaitingUnknownSources(
        val remote: RemoteUpdate,
        val apkFile: File,
    ) : StartupUpdateOutcome

    data class InstallerLaunched(
        val remote: RemoteUpdate,
        val apkFile: File,
    ) : StartupUpdateOutcome
}

class StartupUpdater(
    private val context: Context,
    private val feedClient: UpdateFeedClient,
    private val downloadManager: ApkDownloadManager,
    private val validator: ApkUpdateValidator,
    private val installer: ApkInstaller,
    private val stateStore: UpdateStateStore,
    private val updateCheckTimeoutMs: Long,
    private val installedVersionCodeProvider: (() -> Int)? = null,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    suspend fun checkForEligibleUpdate(
        onState: (StartupUpdateState) -> Unit,
    ): Result<RemoteUpdate?> {
        onState(StartupUpdateState.Checking)
        val remoteResult = withTimeoutOrNull(updateCheckTimeoutMs) {
            feedClient.latestMainPrerelease()
        } ?: return Result.failure(IOException("Update check timed out"))

        val remote = remoteResult.getOrElse { error ->
            return Result.failure(error)
        }
        if (remote == null) {
            onState(StartupUpdateState.NoUpdate)
            return Result.success(null)
        }

        val installedVersionCode = installedVersionCodeProvider?.invoke() ?: readInstalledVersionCode(context)
        if (remote.versionCode <= installedVersionCode) {
            onState(StartupUpdateState.NoUpdate)
            stateStore.setLastSeenReleaseId(remote.releaseId)
            stateStore.clearPendingInstall()
            return Result.success(null)
        }

        val snapshot = stateStore.snapshot()
        if (snapshot.ignoredReleaseId == remote.releaseId) {
            onState(StartupUpdateState.NoUpdate)
            return Result.success(null)
        }

        val remindLaterReleaseId = snapshot.remindLaterReleaseId
        val remindUntil = snapshot.remindLaterUntilEpochMs
        if (remindLaterReleaseId == remote.releaseId && remindUntil != null) {
            if (nowProvider() < remindUntil) {
                onState(StartupUpdateState.NoUpdate)
                return Result.success(null)
            }
            stateStore.setRemindLater(releaseId = null, untilEpochMs = null)
        }

        if (
            (snapshot.ignoredReleaseId != null && snapshot.ignoredReleaseId != remote.releaseId) ||
            (remindLaterReleaseId != null && remindLaterReleaseId != remote.releaseId)
        ) {
            stateStore.clearPromptDeferrals()
        }

        onState(StartupUpdateState.AwaitingUserChoice(remote))
        return Result.success(remote)
    }

    suspend fun installUpdate(
        remote: RemoteUpdate,
        onState: (StartupUpdateState) -> Unit,
    ): StartupUpdateOutcome {
        val updateFile = resolveUpdateFile(remote, onState)
            ?: return StartupUpdateOutcome.ContinueToAppWithError("Could not download update APK")

        onState(StartupUpdateState.Validating)
        val validation = validator.validate(
            apkFile = updateFile,
            expectedVersionCode = remote.versionCode,
        )
        if (validation.isFailure) {
            stateStore.clearPendingInstall()
            return StartupUpdateOutcome.ContinueToAppWithError(
                validation.exceptionOrNull()?.message ?: "Downloaded update is invalid",
            )
        }

        stateStore.setPendingInstall(remote.releaseId, remote.versionCode)
        onState(StartupUpdateState.Installing)
        return launchInstaller(remote = remote, apkFile = updateFile)
    }

    fun onUserChoseNo(remote: RemoteUpdate) {
        stateStore.setIgnoredRelease(remote.releaseId)
        stateStore.setRemindLater(releaseId = null, untilEpochMs = null)
        stateStore.clearPendingInstall()
    }

    fun onUserChoseRemindLater(remote: RemoteUpdate, nowEpochMs: Long = nowProvider()) {
        stateStore.setIgnoredRelease(null)
        stateStore.setRemindLater(
            releaseId = remote.releaseId,
            untilEpochMs = nowEpochMs + REMIND_LATER_WINDOW_MS,
        )
        stateStore.clearPendingInstall()
    }

    fun onUserChoseYes(remote: RemoteUpdate) {
        val snapshot = stateStore.snapshot()
        if (snapshot.ignoredReleaseId == remote.releaseId) {
            stateStore.setIgnoredRelease(null)
        }
        if (snapshot.remindLaterReleaseId == remote.releaseId) {
            stateStore.setRemindLater(releaseId = null, untilEpochMs = null)
        }
    }

    fun retryPendingInstall(
        remote: RemoteUpdate,
        onState: (StartupUpdateState) -> Unit,
    ): StartupUpdateOutcome {
        val file = downloadManager.outputFile()
        if (!file.exists()) {
            stateStore.clearPendingInstall()
            return StartupUpdateOutcome.ContinueToAppWithError("Update file missing; continuing with current app")
        }
        onState(StartupUpdateState.Installing)
        return launchInstaller(remote = remote, apkFile = file)
    }

    fun pendingSnapshot(): UpdateStateSnapshot = stateStore.snapshot()

    fun clearPendingInstall() {
        stateStore.clearPendingInstall()
    }

    private suspend fun resolveUpdateFile(
        remote: RemoteUpdate,
        onState: (StartupUpdateState) -> Unit,
    ): File? {
        val snapshot = stateStore.snapshot()
        val outputFile = downloadManager.outputFile()
        if (snapshot.pendingInstallReleaseId == remote.releaseId && outputFile.exists()) {
            return outputFile
        }

        val downloadResult = downloadManager.download(remote) { progress ->
            onState(StartupUpdateState.Downloading(progress))
        }
        return downloadResult.getOrNull()
    }

    private fun launchInstaller(
        remote: RemoteUpdate,
        apkFile: File,
    ): StartupUpdateOutcome {
        val result = installer.launchInstaller(apkFile)
        return if (result.isSuccess) {
            val changelog = PendingPostInstallChangelog(
                releaseId = remote.releaseId,
                versionCode = remote.versionCode,
                tagName = remote.tagName,
                commitShaShort = remote.commitShaShort,
                releaseName = remote.releaseName,
                changelogMarkdown = remote.changelogMarkdown,
                changelogSections = remote.changelogSections,
            )
            stateStore.setLastSeenReleaseId(remote.releaseId)
            stateStore.setPendingPostInstallChangelog(changelog)
            stateStore.setLastInstalledChangelog(changelog)
            StartupUpdateOutcome.InstallerLaunched(remote = remote, apkFile = apkFile)
        } else {
            val error = result.exceptionOrNull()
            if (error is UnknownSourcesPermissionRequiredException) {
                StartupUpdateOutcome.AwaitingUnknownSources(remote = remote, apkFile = apkFile)
            } else {
                stateStore.clearPendingInstall()
                StartupUpdateOutcome.ContinueToAppWithError(
                    error?.message ?: "Could not launch Android installer",
                )
            }
        }
    }

    private fun readInstalledVersionCode(context: Context): Int {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return PackageInfoCompat.getLongVersionCode(packageInfo).toInt()
    }
}
