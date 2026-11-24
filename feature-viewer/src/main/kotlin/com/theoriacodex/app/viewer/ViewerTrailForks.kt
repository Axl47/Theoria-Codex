package com.theoriacodex.app.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.app.ui.navigation.TrailFork
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

@Stable
internal class ViewerTrailForks(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    var busy by mutableStateOf<TrailFork?>(null)
        private set

    var failed by mutableStateOf<TrailFork?>(null)
        private set

    private var from: PostId? = null
    private var job: Job? = null

    fun open(chip: TrailFork, post: PostId, start: suspend () -> Boolean): Boolean {
        if (busy != null) return false
        from = post
        busy = chip
        failed = null
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            val opened = try {
                start()
            } finally {
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

    fun follow(post: PostId) {
        if (post == from) return
        from = post
        leave()
    }

    fun leave() {
        job?.cancel()
        job = null
        busy = null
        failed = null
    }

    fun close() = scope.cancel()
}

private const val FAILED_FORK_MS = 2_500L
