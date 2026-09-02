package com.theoriacodex.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.ui.routes.activeRecommendationProfile
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.RecommendationProfile
import kotlinx.coroutines.flow.first

internal fun isCodexImportUri(context: Context, uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase().orEmpty()
    if (scheme != "content" && scheme != "file") return false

    val path = uri.path?.lowercase().orEmpty()
    val lastSegment = uri.lastPathSegment?.lowercase().orEmpty()
    if (path.endsWith(".json") || lastSegment.endsWith(".json")) return true

    val mimeType = runCatching { context.contentResolver.getType(uri) }
        .getOrNull()
        ?.lowercase()
    return mimeType == null ||
        mimeType in CODEX_IMPORT_MIME_TYPES ||
        mimeType == "application/octet-stream"
}

@Composable
internal fun ChangelogBulletText(bullet: String) {
    val leadingSpaces = bullet.takeWhile { it == ' ' }.length
    val indentLevel = (leadingSpaces / 2).coerceAtLeast(0)
    val normalized = bullet.trimStart()
    Text(
        text = "• $normalized",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = (indentLevel * 14).dp),
    )
}

internal fun openUnknownSourcesSettings(context: Context) {
    runCatching {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun installedAppVersionCode(context: Context): Int {
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

internal suspend fun DataDependencies.currentSettingsSnapshot(): AppSettings {
    return settingsRepository.observeSettings().first()
}

internal suspend fun DataDependencies.currentActiveRecommendationProfile(): RecommendationProfile {
    return currentSettingsSnapshot().activeRecommendationProfile()
}

internal fun parseGelbooruProfileOwner(html: String): String? {
    val owner = GELBOORU_PROFILE_OWNER_REGEX.find(html)?.groupValues?.getOrNull(1)
    return owner?.trim()?.takeIf(String::isNotBlank)
}

private val CODEX_IMPORT_MIME_TYPES = setOf("application/json", "text/json")
private val GELBOORU_PROFILE_OWNER_REGEX = Regex("""user:([A-Za-z0-9_:-]+)""", RegexOption.IGNORE_CASE)
