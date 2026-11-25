package com.theoriacodex.app.viewer

import android.content.Context
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.SuccessResult
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val ACCENT_SAMPLE_SIZE = 48
private const val HUE_BUCKETS = 24
private const val MIN_SATURATION = 0.25f

private const val SKIN_HUE_MIN = 10f
private const val SKIN_HUE_MAX = 50f
private const val SKIN_SATURATION_MAX = 0.7f
private const val SKIN_WEIGHT = 0.15f
private const val MIN_BRIGHTNESS = 0.2f

private const val MIN_COLORFUL_SHARE = 0.06f
private const val ACCENT_SATURATION = 0.85f
private const val ACCENT_LIGHTNESS = 0.72f

internal fun viewerAccentFromPixels(pixels: IntArray): Int? {
    if (pixels.isEmpty()) return null
    val weights = FloatArray(HUE_BUCKETS)
    val hueSums = FloatArray(HUE_BUCKETS)
    var colorful = 0
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
        val saturation = range / high
        val skin = hue in SKIN_HUE_MIN..SKIN_HUE_MAX && saturation < SKIN_SATURATION_MAX
        val weight = saturation * saturation * high * if (skin) SKIN_WEIGHT else 1f
        weights[bucket] += weight
        hueSums[bucket] += hue * weight
        colorful++
    }
    if (colorful.toFloat() / pixels.size < MIN_COLORFUL_SHARE) return null
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

private suspend fun loadViewerAccent(context: Context, post: Post, cachedOnly: Boolean): Color? {
    val location = post.preview.localPath ?: post.preview.url ?: return null
    val request = buildViewerImageRequest(context, location, post.id.source, staticAnimatedWebPFrame = true)
        .newBuilder().size(ACCENT_SAMPLE_SIZE).allowHardware(false)
        .networkCachePolicy(if (cachedOnly) CachePolicy.DISABLED else CachePolicy.ENABLED).build()
    val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable ?: return null
    return withContext(Dispatchers.Default) {
        val bitmap = drawable.toBitmap(ACCENT_SAMPLE_SIZE, ACCENT_SAMPLE_SIZE)
        val pixels = IntArray(ACCENT_SAMPLE_SIZE * ACCENT_SAMPLE_SIZE)
        bitmap.getPixels(pixels, 0, ACCENT_SAMPLE_SIZE, 0, 0, ACCENT_SAMPLE_SIZE, ACCENT_SAMPLE_SIZE)
        viewerAccentFromPixels(pixels)?.let(::Color) ?: Color.White
    }
}

internal fun interface ViewerAccentSampler {
    suspend fun sample(post: Post, cachedOnly: Boolean): Color?
}

@Stable
internal class ViewerAccentState(private val pager: State<PagerState>, private val posts: State<List<Post>>) : ColorProducer {
    private val samples = mutableStateMapOf<PostId, Color>()
    private val reveals = mutableStateMapOf<PostId, Float>()
    private var settledId by mutableStateOf<PostId?>(null)
    private var settledPager: PagerState? = null
    private var settledFrom by mutableStateOf(Color.White)
    private var settledTo by mutableStateOf(Color.White)
    private var progress by mutableFloatStateOf(1f)
    private val blended = derivedStateOf { blend() }

    override fun invoke(): Color = blended.value

    private fun settledColor(): Color = lerp(settledFrom, settledTo, progress)

    private fun colorAt(page: Int, settled: Color): Color {
        val id = posts.value.getOrNull(page)?.id ?: return settled
        if (id == settledId) return settled
        val sample = samples[id] ?: return settled
        return reveals[id]?.let { lerp(settled, sample, it) } ?: sample
    }

    private fun blend(): Color {
        val pager = pager.value
        val settled = settledColor()
        val position = pager.currentPage + pager.currentPageOffsetFraction
        val lower = floor(position).toInt()
        return lerp(colorAt(lower, settled), colorAt(lower + 1, settled), position - lower)
    }

    fun settle(id: PostId, index: Int) {
        val pager = pager.value
        val samePager = settledPager === pager
        settledPager = pager
        if (id == settledId) return
        val settled = settledColor()
        val shown = when {
            !samePager -> settled
            abs(pager.currentPage + pager.currentPageOffsetFraction - index) < 1f -> colorAt(index, settled)
            else -> blend()
        }
        retarget(shown, samples[id] ?: shown)
        settledId = id
    }

    private fun retarget(from: Color, to: Color) {
        settledFrom = from
        settledTo = to
        progress = 0f
    }

    suspend fun load(post: Post, sampler: ViewerAccentSampler) {
        if (samples[post.id] == null) {
            val color = sampler.sample(post, cachedOnly = false) ?: Color.White
            samples[post.id] = color
            if (settledId == post.id) retarget(settledColor(), color)
        }
        animate(progress, 1f, animationSpec = tween(TheoriaMotion.MEDIUM_MS)) { value, _ -> progress = value }
    }

    suspend fun sampleNeighbours(neighbours: List<Post>, sampler: ViewerAccentSampler) = coroutineScope {
        neighbours.filter { samples[it.id] == null }.forEach { neighbour ->
            launch { sampler.sample(neighbour, cachedOnly = true)?.let { reveal(neighbour.id, it) } }
        }
    }

    private suspend fun reveal(id: PostId, color: Color) {
        val moving = abs(pager.value.currentPageOffsetFraction) > REST_OFFSET
        if (!moving) {
            samples[id] = color
            return
        }
        reveals[id] = 0f
        samples[id] = color
        try {
            animate(0f, 1f, animationSpec = tween(TheoriaMotion.SHORT_MS)) { value, _ -> reveals[id] = value }
        } finally {
            reveals.remove(id)
        }
    }
}

private const val REST_OFFSET = 0.001f

@Composable
internal fun rememberViewerAccent(
    post: Post,
    index: Int,
    posts: List<Post>,
    pager: PagerState,
    enabled: Boolean,
    sampler: ViewerAccentSampler = rememberViewerAccentSampler(),
): ViewerAccentState {
    val pagerState = rememberUpdatedState(pager)
    val postsState = rememberUpdatedState(posts)
    val accent = remember { ViewerAccentState(pagerState, postsState) }
    SideEffect { if (enabled) accent.settle(post.id, index) }
    LaunchedEffect(post.id, enabled) {
        if (enabled) accent.load(post, sampler)
    }
    val before = posts.getOrNull(index - 1)
    val after = posts.getOrNull(index + 1)
    LaunchedEffect(before?.id, after?.id, enabled) {
        if (enabled) accent.sampleNeighbours(listOfNotNull(before, after), sampler)
    }
    return accent
}

@Composable
private fun rememberViewerAccentSampler(): ViewerAccentSampler {
    val context = LocalContext.current
    return remember(context) { ViewerAccentSampler { post, cachedOnly -> loadViewerAccent(context, post, cachedOnly) } }
}
