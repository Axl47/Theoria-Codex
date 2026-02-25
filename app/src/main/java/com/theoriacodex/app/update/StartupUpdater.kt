package com.theoriacodex.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import kotlinx.coroutines.withTimeoutOrNull

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
) {
    suspend fun run(
        onState: (StartupUpdateState) -> Unit,
    ): StartupUpdateOutcome {
        onState(StartupUpdateState.Checking)
        val remoteResult = withTimeoutOrNull(updateCheckTimeoutMs) {
            feedClient.latestMainPrerelease()
        } ?: return StartupUpdateOutcome.ContinueToAppWithError("Update check timed out")

        val remote = remoteResult.getOrElse { error ->
            return StartupUpdateOutcome.ContinueToAppWithError(
                error.message ?: "Could not check for updates",
            )
        }
        if (remote == null) {
            onState(StartupUpdateState.NoUpdate)
            return StartupUpdateOutcome.ContinueToApp
        }

        val installedVersionCode = installedVersionCode()
        if (remote.versionCode <= installedVersionCode) {
            onState(StartupUpdateState.NoUpdate)
            stateStore.setLastSeenReleaseId(remote.releaseId)
            stateStore.clearPendingInstall()
            return StartupUpdateOutcome.ContinueToApp
        }

        onState(StartupUpdateState.UpdateFound(remote))
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
            stateStore.setLastSeenReleaseId(remote.releaseId)
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

    private fun installedVersionCode(): Int {
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
