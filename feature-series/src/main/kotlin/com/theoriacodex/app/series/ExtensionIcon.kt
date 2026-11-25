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
import coil.compose.AsyncImage
import coil.request.ImageRequest

internal const val EXTENSION_ICON_TAG = "extension-icon"

@Composable
internal fun ExtensionIcon(url: String?) {
    val context = LocalContext.current
    val request = remember(context, url) { url?.let { ImageRequest.Builder(context).data(it).allowHardware(false).build() } }
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

private val ICON_SIZE = 40.dp
private val ICON_SHAPE = RoundedCornerShape(8.dp)
