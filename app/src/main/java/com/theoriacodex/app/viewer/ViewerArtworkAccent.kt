package com.theoriacodex.app.viewer

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.SuccessResult
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val ACCENT_SAMPLE_SIZE = 24
private const val HUE_BUCKETS = 24
private const val MIN_SATURATION = 0.25f
private const val MIN_BRIGHTNESS = 0.2f

/** Below this share of colorful weight the art counts as monochrome, such as manga, and keeps white. */
private const val MIN_COLORFUL_SHARE = 0.06f
private const val ACCENT_SATURATION = 0.85f
private const val ACCENT_LIGHTNESS = 0.72f

/**
 * Picks the artwork's dominant hue, then fixes saturation and lightness so the accent always reads
 * over the dark scrims, however dark or pale the art is. Returns null for art without a clear color.
 */
internal fun viewerAccentFromPixels(pixels: IntArray): Int? {
    if (pixels.isEmpty()) return null
    val weights = FloatArray(HUE_BUCKETS)
    val hueSums = FloatArray(HUE_BUCKETS)
    var total = 0f
    for (pixel in pixels) {
        if (pixel ushr 24 < 128) continue
        val r = (pixel shr 16 and 0xFF) / 255f
        val g = (pixel shr 8 and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val high = max(r, max(g, b))
        val range = high - min(r, min(g, b))
        if (high < MIN_BRIGHTNESS || range / high < MIN_SATURATION) continue
        val hue = when (high) {
            r -> ((g - b) / range).mod(6f)
            g -> (b - r) / range + 2f
            else -> (r - g) / range + 4f
        } * 60f
        val bucket = (hue / 360f * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
        val weight = range / high * high
        weights[bucket] += weight
        hueSums[bucket] += hue * weight
        total += weight
    }
    if (total / pixels.size < MIN_COLORFUL_SHARE) return null
    val best = weights.indices.maxBy { weights[it] }
    return accentFromHue(hueSums[best] / weights[best])
}

private fun accentFromHue(hue: Float): Int {
    val chroma = (1f - abs(2f * ACCENT_LIGHTNESS - 1f)) * ACCENT_SATURATION
    val floor = ACCENT_LIGHTNESS - chroma / 2f
    val secondary = chroma * (1f - abs((hue / 60f).mod(2f) - 1f))
    val (r, g, b) = when ((hue / 60f).toInt()) {
        0 -> Triple(chroma, secondary, 0f)
        1 -> Triple(secondary, chroma, 0f)
        2 -> Triple(0f, chroma, secondary)
        3 -> Triple(0f, secondary, chroma)
        4 -> Triple(secondary, 0f, chroma)
        else -> Triple(chroma, 0f, secondary)
    }
    return Color(r + floor, g + floor, b + floor).toArgb()
}

/** Samples the post's thumbnail, which the feed has normally already cached, as a tiny software bitmap. */
private suspend fun loadViewerAccent(context: Context, post: Post): Int? {
    val location = post.preview.localPath ?: post.preview.url ?: return null
    val request = buildViewerImageRequest(context, location, post.id.source, staticAnimatedWebPFrame = true)
        .newBuilder().size(ACCENT_SAMPLE_SIZE).allowHardware(false).build()
    val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable ?: return null
    return withContext(Dispatchers.Default) {
        val bitmap = drawable.toBitmap(ACCENT_SAMPLE_SIZE, ACCENT_SAMPLE_SIZE)
        val pixels = IntArray(ACCENT_SAMPLE_SIZE * ACCENT_SAMPLE_SIZE)
        bitmap.getPixels(pixels, 0, ACCENT_SAMPLE_SIZE, 0, 0, ACCENT_SAMPLE_SIZE, ACCENT_SAMPLE_SIZE)
        viewerAccentFromPixels(pixels)
    }
}

/**
 * The selected post's accent, cross-fading between posts. Sampling waits on [enabled] so it never
 * competes with the Viewer's opening animation, and swiping past a post cancels its sample.
 */
@Composable
internal fun rememberViewerAccent(post: Post, enabled: Boolean): Color {
    val context = LocalContext.current
    val samples = remember { HashMap<PostId, Color>() }
    var target by remember { mutableStateOf(Color.White) }
    LaunchedEffect(post.id, enabled) {
        if (!enabled) return@LaunchedEffect
        target = samples.getOrPut(post.id) { loadViewerAccent(context, post)?.let(::Color) ?: Color.White }
    }
    val accent by animateColorAsState(target, tween(TheoriaMotion.MEDIUM_MS), label = "Viewer accent")
    return accent
}
