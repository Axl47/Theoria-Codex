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
        val installedVersionCode = currentInstalledVersionCode()
        reconcilePendingInstallation(installedVersionCode)
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

        if (remote.versionCode <= installedVersionCode) {
            onState(StartupUpdateState.NoUpdate)
            stateStore.update { current ->
                current.copy(
                    lastSeenReleaseId = remote.releaseId,
                    pendingInstallReleaseId = null,
                    pendingInstallVersionCode = null,
                )
            }
            return Result.success(null)
        }

        val snapshot = stateStore.snapshot()
        if (snapshot.ignoredReleaseId == remote.releaseId) {
            onState(StartupUpdateState.NoUpdate)
            return Result.success(null)
        }

        val remindLaterReleaseId = snapshot.remindLaterReleaseId
        val remindUntil = snapshot.remindLaterUntilEpochMs
        var matchingReminderExpired = false
        if (remindLaterReleaseId == remote.releaseId && remindUntil != null) {
            if (nowProvider() < remindUntil) {
                onState(StartupUpdateState.NoUpdate)
                return Result.success(null)
            }
            matchingReminderExpired = true
        }

        val hasStalePromptDecision =
            (snapshot.ignoredReleaseId != null && snapshot.ignoredReleaseId != remote.releaseId) ||
            (remindLaterReleaseId != null && remindLaterReleaseId != remote.releaseId)
        if (matchingReminderExpired || hasStalePromptDecision) {
            stateStore.update { current ->
                current.copy(
                    ignoredReleaseId = if (hasStalePromptDecision) null else current.ignoredReleaseId,
                    remindLaterReleaseId = null,
                    remindLaterUntilEpochMs = null,
                )
            }
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

    suspend fun onUserChoseNo(remote: RemoteUpdate) {
        stateStore.update { current ->
            current.copy(
                ignoredReleaseId = remote.releaseId,
                remindLaterReleaseId = null,
                remindLaterUntilEpochMs = null,
                pendingInstallReleaseId = null,
                pendingInstallVersionCode = null,
            )
        }
    }

    suspend fun onUserChoseRemindLater(remote: RemoteUpdate, nowEpochMs: Long = nowProvider()) {
        stateStore.update { current ->
            current.copy(
                ignoredReleaseId = null,
                remindLaterReleaseId = remote.releaseId,
                remindLaterUntilEpochMs = nowEpochMs + REMIND_LATER_WINDOW_MS,
                pendingInstallReleaseId = null,
                pendingInstallVersionCode = null,
            )
        }
    }

    suspend fun onUserChoseYes(remote: RemoteUpdate) {
        stateStore.update { current ->
            current.copy(
                ignoredReleaseId = current.ignoredReleaseId.takeUnless { releaseId ->
                    releaseId == remote.releaseId
                },
                remindLaterReleaseId = current.remindLaterReleaseId.takeUnless { releaseId ->
                    releaseId == remote.releaseId
                },
                remindLaterUntilEpochMs = current.remindLaterUntilEpochMs.takeUnless {
                    current.remindLaterReleaseId == remote.releaseId
                },
            )
        }
    }

    suspend fun retryPendingInstall(
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

    suspend fun pendingSnapshot(): UpdateStateSnapshot = stateStore.snapshot()

    suspend fun clearPendingInstall() {
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

    private suspend fun launchInstaller(
        remote: RemoteUpdate,
        apkFile: File,
    ): StartupUpdateOutcome {
        val result = installer.launchInstaller(apkFile)
        return if (result.isSuccess) {
            val changelog = PendingPostInstallChangelog(
                releaseId = remote.releaseId,
                fromVersionCode = currentInstalledVersionCode(),
                versionCode = remote.versionCode,
                tagName = remote.tagName,
                commitShaShort = remote.commitShaShort,
                releaseName = remote.releaseName,
                changelogMarkdown = remote.changelogMarkdown,
                changelogSections = remote.changelogSections,
            )
            stateStore.update { current ->
                current.copy(
                    pendingPostInstallChangelog = changelog,
                )
            }
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

    private suspend fun reconcilePendingInstallation(installedVersionCode: Int) {
        val snapshot = stateStore.snapshot()
        val pending = snapshot.pendingPostInstallChangelog ?: return
        val alreadyPromoted =
            snapshot.lastInstalledChangelog == pending &&
                snapshot.lastSeenReleaseId == pending.releaseId &&
                snapshot.pendingInstallReleaseId == null &&
                snapshot.pendingInstallVersionCode == null
        if (installedVersionCode < pending.versionCode ||
            (installedVersionCode == pending.versionCode && alreadyPromoted)
        ) {
            return
        }

        stateStore.update { current ->
            val currentPending = current.pendingPostInstallChangelog ?: return@update current
            when {
                installedVersionCode == currentPending.versionCode -> current.copy(
                    lastSeenReleaseId = currentPending.releaseId,
                    pendingInstallReleaseId = null,
                    pendingInstallVersionCode = null,
                    lastInstalledChangelog = currentPending,
                )

                installedVersionCode > currentPending.versionCode -> current.copy(
                    pendingInstallReleaseId = null,
                    pendingInstallVersionCode = null,
                    pendingPostInstallChangelog = null,
                )

                else -> current
            }
        }
    }

    private fun currentInstalledVersionCode(): Int {
        return installedVersionCodeProvider?.invoke() ?: readInstalledVersionCode(context)
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
