@file:Suppress("DEPRECATION")

package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class ViewerGifFrameRenderer(
    private val width: Int,
    private val height: Int,
    private val drawFrame: (Canvas, positionMs: Int) -> Unit,
) {
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    private val requested = AtomicInteger(-1)
    private val scheduled = AtomicBoolean(false)
    private val renderTask = Runnable(::renderLatest)
    @Volatile private var closed = false
    private var shown: Bitmap? = null
    private var spare: Bitmap? = null
    private var renderedPosition = -1
    private var buffersCreated = 0

    fun request(positionMs: Int) {
        requested.set(positionMs.coerceAtLeast(0))
        schedule()
    }

    fun close() {
        closed = true
        shown = null
        frame = null
        gifRenderThread.execute { spare = null }
    }

    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) gifRenderThread.execute(renderTask)
    }

    private fun renderLatest() {
        scheduled.set(false)
        if (closed) return
        val position = requested.get()
        if (position < 0 || position == renderedPosition) return
        val target = spare ?: newBuffer() ?: return
        spare = null
        renderedPosition = position
        traceMediaSection(MediaTraceSections.VIEWER_GIF_RENDER) {
            target.eraseColor(0)
            drawFrame(Canvas(target), position)
        }
        mainHandler.post { publish(target) }
    }

    private fun newBuffer(): Bitmap? {
        if (buffersCreated >= GIF_RENDER_BUFFERS) return null
        buffersCreated += 1
        return Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    }

    private fun publish(bitmap: Bitmap) {
        if (closed) return
        val previous = shown
        shown = bitmap
        frame = bitmap.asImageBitmap()
        gifRenderThread.execute { if (previous != null) spare = previous; schedule() }
    }

    companion object {
        fun of(movie: Movie) = ViewerGifFrameRenderer(movie.width(), movie.height()) { canvas, positionMs ->
            movie.setTime(positionMs)
            movie.draw(canvas, 0f, 0f)
        }

        private const val GIF_RENDER_BUFFERS = 2
        private val mainHandler = Handler(Looper.getMainLooper())
        private val gifRenderThread: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ViewerGifRender").apply { isDaemon = true }
        }
    }
}
