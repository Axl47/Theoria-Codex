package com.theoriacodex.app.source

import android.net.Uri
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
                    .data(Uri.parse("android.resource://${context.packageName}/${logo.resourceId}"))
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
