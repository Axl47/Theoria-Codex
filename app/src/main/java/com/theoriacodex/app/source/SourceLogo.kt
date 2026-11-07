package com.theoriacodex.app.source

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.theoriacodex.domain.model.SourceKey

/** Renders the logo decision owned by [SourcePresentationCatalog]. */
@Composable
fun SourceLogo(
    source: SourceKey,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val presentation = source.presentation()
    when (val logo = presentation.logo) {
        is SourceLogoAsset.Drawable -> {
            Image(
                painter = painterResource(id = logo.resourceId),
                contentDescription = presentation.label,
                modifier = if (source == SourceKey.HITOMI) modifier.size(size) else modifier.height(size),
                contentScale = ContentScale.Fit,
            )
        }

        is SourceLogoAsset.RawSvg -> {
            val context = LocalContext.current
            val model = remember(context, logo.resourceId) {
                ImageRequest.Builder(context)
                    .data("android.resource://${context.packageName}/${logo.resourceId}".toUri())
                    .decoderFactory(SvgDecoder.Factory())
                    .build()
            }
            AsyncImage(
                model = model,
                contentDescription = presentation.label,
                modifier = modifier.height(size),
                contentScale = ContentScale.Fit,
            )
        }

        SourceLogoAsset.Text -> {
            Text(
                text = presentation.label,
                modifier = modifier,
                style = MaterialTheme.typography.labelMedium,
                color = if (source.isRule34Family()) Color.White else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** Compact source selector mark; sources without a legible small logo use a letter mark. */
@Composable
fun SourceIcon(source: SourceKey) {
    source.presentation().mark?.let { SourceLetterIcon(it) } ?: SourceLogo(source, size = 18.dp)
}

/** Letter marks share the white, bold treatment of Hitomi's icon. */
@Composable
fun SourceLetterIcon(label: String) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}
