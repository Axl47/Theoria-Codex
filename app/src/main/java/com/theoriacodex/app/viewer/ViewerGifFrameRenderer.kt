@file:Suppress("DEPRECATION") // Movie is kept because it seeks to any time, which drives scrubbing and speed control.

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

/**
 * Renders GIF frames off the main thread into two alternating [width] x [height] bitmaps, so a frame is
 * decoded on [gifRenderThread] instead of in the draw pass. [drawFrame] draws the frame at a position
 * into a cleared canvas; [of] adapts a [Movie]. Call [request] on the main thread with each position;
 * only the newest request is rendered, so scrubbing never builds a backlog. [frame] holds the newest
 * finished bitmap. Call [close] when the caller leaves composition.
 *
 * Every Viewer GIF shares one render thread, so all `setTime`/`draw` calls on a [Movie], which is not
 * thread-safe, are serialized even when the page and its scrub preview render the same instance.
 * A bitmap is drawn into only after the main thread has replaced it with a newer frame, so the
 * bitmap on screen is never written while it is displayed.
 */
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
    private var shown: Bitmap? = null // main thread
    private var spare: Bitmap? = null // render thread
    private var renderedPosition = -1 // render thread
    private var buffersCreated = 0 // render thread

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
        val target = spare ?: newBuffer() ?: return // Both buffers are out; publish() reschedules.
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
        // Draws only happen in the next frame, which now reads [bitmap], so [previous] is free to reuse.
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
