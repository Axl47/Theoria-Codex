package com.theoriacodex.app.viewer

import android.content.Context
import android.os.SystemClock
import coil.imageLoader
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import com.theoriacodex.app.media.viewerMediaDeliveryPlan
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.domain.model.Post

internal fun ViewerViewModel.queueAdjacentPrefetch() {
    onAction(viewerPrefetchAction(state.value.pages, state.value.currentMedia?.key, null))
}

internal fun ViewerViewModel.markShown(mediaIndex: Int) {
    val current = state.value.currentMedia?.takeIf { it.key.mediaIndex == mediaIndex } ?: return
    onAction(ViewerAction.MediaReady(requireNotNull(state.value.session), current.key, current.loadGeneration))
}

internal fun ViewerViewModel.selectAndPrefetch(mediaIndex: Int) {
    onAction(ViewerAction.SelectMedia(mediaIndex))
    queueAdjacentPrefetch()
}

internal suspend fun Context.displayViewerPage(post: Post, index: Int): Shown {
    val location = viewerMediaDeliveryPlan(post, post.media[index]).candidates.first().location
    val metrics = resources.displayMetrics
    val request = buildViewerImageRequest(this, location, post.id.source).newBuilder()
        .size(metrics.widthPixels, metrics.heightPixels)
        .precision(Precision.INEXACT)
        .scale(Scale.FIT)
        .build()
    val started = SystemClock.elapsedRealtime()
    val result = imageLoader.execute(request)
    check(result is SuccessResult) { "Gallery page $index failed: $result" }
    return Shown(SystemClock.elapsedRealtime() - started, result.dataSource.name.lowercase())
}

internal fun Context.pagesReadyAhead(post: Post, current: Int): Int =
    (current + 1..minOf(current + READY_AHEAD_WINDOW, post.media.lastIndex)).count { index ->
        val location = requireNotNull(post.media[index].url)
        imageLoader.diskCache?.openSnapshot(location)?.use { true } == true
    }

internal data class Shown(val elapsedMs: Long, val source: String)

private const val READY_AHEAD_WINDOW = 10
