@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.benchmark

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.descendants
import androidx.media3.ui.PlayerView
import java.lang.ref.WeakReference

/** Reads the fitted native video surface, which gesture accessibility nodes can occlude. */
internal object BenchmarkViewerObservation {
    private var foregroundRoot: WeakReference<ViewGroup>? = null

    fun attach(root: ViewGroup) {
        foregroundRoot = WeakReference(root)
    }

    fun detach(root: View) {
        if (foregroundRoot?.get() === root) foregroundRoot = null
    }

    fun snapshot(): Bundle? {
        val root = foregroundRoot?.get() ?: return null
        val videos = root.descendants.filterIsInstance<PlayerView>().mapNotNull { view ->
            val identity = (view.tag as? String)?.takeIf { it.startsWith("viewer_video_") }
                ?: return@mapNotNull null
            val surface = view.videoSurfaceView ?: return@mapNotNull null
            val bounds = Rect()
            if (!surface.isShown || !surface.getGlobalVisibleRect(bounds) || bounds.isEmpty) {
                return@mapNotNull null
            }
            Bundle().apply {
                putString("identity", identity)
                putInt("left", bounds.left)
                putInt("top", bounds.top)
                putInt("right", bounds.right)
                putInt("bottom", bounds.bottom)
            }
        }.toList()
        return videos.singleOrNull()
    }
}
