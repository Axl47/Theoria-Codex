package com.theoriacodex.app.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

class AndroidApkInstaller(
    private val context: Context,
) : ApkInstaller {
    override fun launchInstaller(apkFile: File): Result<Unit> {
        return runCatching {
            if (!context.packageManager.canRequestPackageInstalls()) {
                throw UnknownSourcesPermissionRequiredException()
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            context.startActivity(buildApkInstallerIntent(contentUri))
        }
    }

    fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

internal fun buildApkInstallerIntent(contentUri: Uri): Intent {
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(contentUri, APK_MIME_TYPE)
        clipData = ClipData.newRawUri("update_apk", contentUri)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_RETURN_RESULT, true)
    }
}

internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"
