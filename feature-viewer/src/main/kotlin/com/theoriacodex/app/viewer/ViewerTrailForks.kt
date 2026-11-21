package com.theoriacodex.app.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The trail forks of one Viewer level, shared by its trail chips and its info sheet's tags: at most one loads at a time.
 * A fork runs in [scope], which lives as long as the level ([close]), so the chrome hiding or the sheet closing leaves
 * it running; paging to another post cancels it, as soon as the swipe heads there ([leave]) and once it lands ([follow]).
 */
@Stable
internal class ViewerTrailForks(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    /** The chip whose fork is loading. */
    var busy by mutableStateOf<ViewerTrailChip?>(null)
        private set

    /** The chip whose fork just came back empty or failed, for a moment. */
    var failed by mutableStateOf<ViewerTrailChip?>(null)
        private set

    private var from: PostId? = null
    private var job: Job? = null

    /** Starts [chip]'s fork from [post] with [start] (true once it opened); false, starting nothing, while one loads. */
    fun open(chip: ViewerTrailChip, post: PostId, start: suspend () -> Boolean): Boolean {
        if (busy != null) return false
        from = post
        busy = chip
        failed = null
        // Started only once it is [job], since an immediate dispatcher may run it to its end inside launch.
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            val opened = try {
                start()
            } finally {
                // A fork cancelled by [follow] may finish after the next one started; it must not clear that one.
                if (job === self) busy = null
            }
            if (opened || job !== self) return@launch
            failed = chip
            delay(FAILED_FORK_MS)
            if (job === self) failed = null
        }
        job = launched
        launched.start()
        return true
    }

    /** Cancels the fork, and clears its failure, when the post on screen is no longer the one it was asked from. */
    fun follow(post: PostId) {
        if (post == from) return
        from = post
        leave()
    }

    /** Cancels the fork, and clears its failure: a swipe is heading to another post, where it must not open. */
    fun leave() {
        job?.cancel()
        job = null
        busy = null
        failed = null
    }

    /** Cancels any fork for good; the level is gone. */
    fun close() = scope.cancel()
}

private const val FAILED_FORK_MS = 2_500L
