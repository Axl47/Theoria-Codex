package com.theoriacodex.app.source

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import coil.compose.SubcomposeAsyncImage
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

        is SourceLogoAsset.Favicon -> FaviconImage(logo, presentation, modifier.size(size)) {
            SourceLabelText(source, presentation.label, modifier)
        }

        SourceLogoAsset.Text -> SourceLabelText(source, presentation.label, modifier)
    }
}

@Composable
private fun SourceLabelText(source: SourceKey, label: String, modifier: Modifier) {
    Text(
        text = label,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = if (source.isRule34Family()) Color.White else LocalContentColor.current,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

/**
 * A site icon; [fallback] shows while it loads and when the site has none. It is requested with the
 * site's media headers, so a browser-verified site's clearance (bound to its User-Agent) applies.
 */
@Composable
private fun FaviconImage(
    logo: SourceLogoAsset.Favicon,
    presentation: SourcePresentation,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val request = remember(context, logo.url, presentation.userAgent, presentation.referer) {
        ImageRequest.Builder(context)
            .data(logo.url)
            .addHeader("User-Agent", presentation.userAgent)
            .addHeader("Referer", presentation.referer)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = presentation.label,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        // The slots don't center their content, so a letter fallback would sit at the top start.
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() } },
        error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() } },
    )
}

/** Compact source selector mark; sources without a legible small logo use a letter mark. */
@Composable
fun SourceIcon(source: SourceKey) {
    val presentation = source.presentation()
    val logo = presentation.logo
    val mark = presentation.mark
    when {
        logo is SourceLogoAsset.Favicon -> FaviconImage(logo, presentation, Modifier.size(24.dp)) {
            SourceLetterIcon(mark.orEmpty())
        }
        mark != null -> SourceLetterIcon(mark)
        else -> SourceLogo(source, size = 18.dp)
    }
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
