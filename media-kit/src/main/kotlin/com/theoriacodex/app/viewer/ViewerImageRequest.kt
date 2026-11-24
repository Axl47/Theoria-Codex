package com.theoriacodex.app.viewer

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import coil.size.SizeResolver
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * No crossfade: the preview leaves as the image arrives, so fading in would dip through the background.
 * Decodes to hardware bitmaps so a full-size page is not uploaded to the GPU by RenderThread as it settles;
 * callers that read pixels (the artwork accent) opt back into software with `allowHardware(false)`.
 */
fun buildViewerImageRequest(
    context: Context,
    url: String,
    sourceKey: SourceKey,
    staticAnimatedWebPFrame: Boolean = false,
    targetWidthPx: Int? = null,
    targetHeightPx: Int? = null,
): ImageRequest = MediaRequestFactory.imageRequest(
    context = context,
    url = url,
    sourceKey = sourceKey,
    crossfade = false,
    allowHardware = true,
    staticAnimatedWebPFrame = staticAnimatedWebPFrame,
    targetWidthPx = targetWidthPx,
    targetHeightPx = targetHeightPx,
)

/**
 * A Viewer page decoded for the full [pageWidthPx] the vertical reader lays it out at, whatever its shape, as tall as
 * a hardware bitmap can be drawn ([viewerPageDecodeBounds]). The reader lays pages out before their shape is known,
 * so sizing the decode from that slot would shrink a tall strip to a sliver; its prefetch uses the same request and
 * width, so the bitmap it keeps in memory is the one the page shows. Never upscales: a narrower page decodes at its
 * own width. A stopgap until tall strips decode as tiles; nothing outside the vertical reader should size this way.
 */
fun buildViewerPageRequest(context: Context, url: String, sourceKey: SourceKey, pageWidthPx: Int): ImageRequest =
    buildViewerImageRequest(context, url, sourceKey).newBuilder()
        .size(ViewerPageSizeResolver(pageWidthPx))
        .precision(Precision.INEXACT)
        .build()

/** Resolves [viewerPageDecodeBounds] inside Coil's pipeline, so the one-time GPU query never runs on the UI thread. */
private class ViewerPageSizeResolver(private val viewportWidthPx: Int) : SizeResolver {
    override suspend fun size(): Size {
        val maxEdge = if (maxBitmapEdgePx.isInitialized()) maxBitmapEdgePx.value else withContext(Dispatchers.IO) { maxBitmapEdgePx.value }
        val (width, height) = viewerPageDecodeBounds(viewportWidthPx, maxEdge)
        return Size(width, height)
    }
}

/**
 * The box a full-width page fits into: [viewportWidthPx] wide, and as tall as the GPU's [maxEdgePx] and the
 * [MAX_PAGE_BITMAP_BYTES] budget allow (drawing a larger bitmap throws), so a strip too tall for both is scaled down.
 */
internal fun viewerPageDecodeBounds(viewportWidthPx: Int, maxEdgePx: Int): Pair<Int, Int> {
    val width = viewportWidthPx.coerceIn(1, maxEdgePx)
    return width to minOf(maxEdgePx, (MAX_PAGE_BITMAP_BYTES / (width * BYTES_PER_PIXEL)).toInt())
}

/** The longest edge the GPU draws a texture at, read once; [FALLBACK_MAX_BITMAP_EDGE_PX] when it cannot be read. */
private val maxBitmapEdgePx: Lazy<Int> = lazy {
    runCatching(::queryMaxTextureSize).getOrNull()?.takeIf { it > 0 } ?: FALLBACK_MAX_BITMAP_EDGE_PX
}

/**
 * Asks OpenGL ES through a throwaway 1×1 offscreen context, on a background thread that holds no context of its own.
 * The default display is shared with HWUI, so it is never terminated.
 */
private fun queryMaxTextureSize(): Int? {
    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) return null
    val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
    val found = IntArray(1)
    val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
    if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0) || found[0] == 0) return null
    val config = configs[0] ?: return null
    val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
    if (context == EGL14.EGL_NO_CONTEXT) return null
    val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
    var bound = false
    return try {
        bound = surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context)
        if (!bound) return null
        IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
    } finally {
        if (bound) EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
    }
}

/** Vulkan's guaranteed minimum image edge, which every device this app supports meets. */
private const val FALLBACK_MAX_BITMAP_EDGE_PX = 4096

/** Under the 100 MB a hardware canvas refuses to draw, with headroom; tiling very tall strips is the real fix. */
private const val MAX_PAGE_BITMAP_BYTES = 64L * 1024 * 1024
private const val BYTES_PER_PIXEL = 4
