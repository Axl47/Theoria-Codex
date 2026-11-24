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

fun buildViewerPageRequest(context: Context, url: String, sourceKey: SourceKey, pageWidthPx: Int): ImageRequest =
    buildViewerImageRequest(context, url, sourceKey).newBuilder()
        .size(ViewerPageSizeResolver(pageWidthPx))
        .precision(Precision.INEXACT)
        .build()

private class ViewerPageSizeResolver(private val viewportWidthPx: Int) : SizeResolver {
    override suspend fun size(): Size {
        val maxEdge = if (maxBitmapEdgePx.isInitialized()) maxBitmapEdgePx.value else withContext(Dispatchers.IO) { maxBitmapEdgePx.value }
        val (width, height) = viewerPageDecodeBounds(viewportWidthPx, maxEdge)
        return Size(width, height)
    }
}

internal fun viewerPageDecodeBounds(viewportWidthPx: Int, maxEdgePx: Int): Pair<Int, Int> {
    val width = viewportWidthPx.coerceIn(1, maxEdgePx)
    return width to minOf(maxEdgePx, (MAX_PAGE_BITMAP_BYTES / (width * BYTES_PER_PIXEL)).toInt())
}

private val maxBitmapEdgePx: Lazy<Int> = lazy {
    runCatching(::queryMaxTextureSize).getOrNull()?.takeIf { it > 0 } ?: FALLBACK_MAX_BITMAP_EDGE_PX
}

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

private const val FALLBACK_MAX_BITMAP_EDGE_PX = 4096

private const val MAX_PAGE_BITMAP_BYTES = 64L * 1024 * 1024
private const val BYTES_PER_PIXEL = 4
