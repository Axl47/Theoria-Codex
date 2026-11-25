package com.theoriacodex.app.series

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import coil.ComponentRegistry
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.ImageRequest
import coil.size.pxOrElse
import com.theoriacodex.domain.series.ApkIcon
import java.io.IOException

internal const val EXTENSION_ICON_TAG = "extension-icon"

@Composable
internal fun ExtensionIcon(url: String?) = IconSlot(url)

@Composable
internal fun ExtensionIcon(icon: ApkIcon?) = IconSlot(icon)

@Composable
private fun IconSlot(data: Any?) {
    val context = LocalContext.current
    val request = remember(context, data) { data?.let { ImageRequest.Builder(context).data(it).allowHardware(false).build() } }
    Box(
        Modifier
            .size(ICON_SIZE)
            .clip(ICON_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(EXTENSION_ICON_TAG),
    ) {
        if (request != null) AsyncImage(request, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

fun ComponentRegistry.Builder.addExtensionIcons(): ComponentRegistry.Builder =
    add(Fetcher.Factory<ApkIcon> { icon, options, _ ->
        Fetcher {
            val packages = options.context.packageManager
            val app = packages.getPackageArchiveInfo(icon.apk, 0)?.applicationInfo?.takeIf { it.icon != 0 } ?: throw IOException("No icon in ${icon.packageName}")
            app.sourceDir = icon.apk
            app.publicSourceDir = icon.apk
            val side = options.size.width.pxOrElse { APK_ICON_PX }
            val drawable = packages.getResourcesForApplication(app).getDrawable(app.icon, null)
            val bitmap = drawable.toBitmap(side, side)
            DrawableResult(bitmap.toDrawable(options.context.resources), isSampled = false, DataSource.DISK)
        }
    }).add(Keyer<ApkIcon> { icon, _ -> "extension-icon:${icon.packageName}:${icon.versionCode}:${icon.apk}:${icon.modified}" })

private val ICON_SIZE = 40.dp
private val ICON_SHAPE = RoundedCornerShape(8.dp)
private const val APK_ICON_PX = 160
