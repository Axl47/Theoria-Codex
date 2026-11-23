package com.theoriacodex.app.vision

import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tags the pictures of liked and saved posts that the current model has not analysed, and stores what it found beside
 * each post's hash. The app's charging-only background job runs [drain]; [writeThrough] runs while the app does.
 */
class LibraryVisionBackfill(
    private val tagger: PictureTagger,
    private val signatures: VisualSignatureRepository,
    /** Whether the device is warm enough that indexing should wait (thermal status moderate or worse). */
    private val hot: () -> Boolean,
    /**
     * Whether none of the app is on screen, as it changes. A playing post's frames are read only then, so the decoding
     * never competes with a visible card or the Like tap.
     */
    private val away: Flow<Boolean>,
) {
    /**
     * Whether a [drain] left playing posts for the app to leave the screen; [whenAway] takes it. A drain that stops
     * before reaching the end of the backlog (the device ran warm, say) leaves it as it was, since it cannot tell.
     */
    private val framesWaiting = MutableStateFlow(false)

    /**
     * Analyses a batch at a time and rests as long as the batch took, so the CPU works half the time at most. Stops when
     * done, when the model goes or would not load, or when the device runs [hot]; losing the network stops the job
     * itself. Returns whether a later run should try again, which includes a model that would not load but is still
     * installed (memory pressure, say) and pictures that failed to load, which are skipped for the rest of this run.
     * Playing posts read frames too ([PictureTagger.analyzeForLibrary]), but only while the app is [away]; ones it
     * reaches while the app is on screen, or that are cut short when the app comes on screen, wait for [whenAway]
     * instead of a retry.
     */
    suspend fun drain(): Boolean {
        val pass = Pass()
        try {
            while (tagger.ready.value) {
                if (hot()) return true
                // The posts this run left are the backlog's first rows, since newest come first and the rest got stored.
                val batch = signatures.libraryPostsWithoutVision(tagger.modelId, BATCH, offset = pass.left)
                if (batch.isEmpty()) {
                    pass.finished = true
                    return pass.retry
                }
                val started = TimeSource.Monotonic.markNow()
                val results = analyze(batch, pass)
                // What the batch finished is kept even when the model went: a playing post's frames cost a download.
                if (!tagger.store(results)) return false
                if (PictureResult.Unavailable in results.values) return tagger.ready.value
                delay(started.elapsedNow())
            }
            return false
        } finally {
            if (pass.waiting) framesWaiting.value = true else if (pass.finished) framesWaiting.value = false
        }
    }

    /**
     * Calls [schedule] whenever the app is off screen while a [drain] has left frames for that, whichever happened
     * last, so they are read on the next charge without waiting out a retry's back-off. Runs until cancelled.
     */
    suspend fun whenAway(schedule: () -> Unit) {
        combine(away, framesWaiting) { isAway, waiting -> isAway && waiting }.filter { it }.collect {
            framesWaiting.value = false
            schedule()
        }
    }

    /** [batch]'s results in order, up to the device running [hot]; what it leaves for later is counted in [pass]. */
    private suspend fun analyze(batch: List<Post>, pass: Pass): Map<PostId, PictureResult> {
        val results = LinkedHashMap<PostId, PictureResult>()
        for (post in batch) {
            // Checked before each post, since a playing one takes up to seven inferences; drain stores what is done.
            if (hot()) break
            when (val result = if (tagger.plays(post)) whileAway(post) else tagger.analyzeForLibrary(post)) {
                null -> pass.leave(waiting = true)
                PictureResult.Unreachable -> pass.leave(waiting = false)
                else -> results[post.id] = result
            }
        }
        return results
    }

    /** [post]'s Library analysis while the app is [away]; null when the app is on screen, or comes on screen first. */
    private suspend fun whileAway(post: Post): PictureResult? {
        if (!away.first()) return null
        return coroutineScope {
            val analysis = async { tagger.analyzeForLibrary(post) }
            val watch = launch { if (away.firstOrNull { !it } != null) analysis.cancel() }
            // Joining, unlike awaiting, does not throw when only the analysis was cancelled.
            analysis.join()
            watch.cancel()
            if (analysis.isCancelled) null else analysis.await()
        }
    }

    /**
     * What one [drain] left unstored: how many posts, whether any wait for a retry, and whether any wait for [away]; and
     * whether it reached the end of the backlog.
     */
    private class Pass {
        var finished = false
        var left = 0
            private set
        var retry = false
            private set
        var waiting = false
            private set

        fun leave(waiting: Boolean) {
            left++
            if (waiting) this.waiting = true else retry = true
        }
    }

    /**
     * Stores what this session already analysed for posts kept since (a For You card liked after it was vetted, say),
     * whenever the Library gains a post, so those pictures are never read twice. A post that plays is left to [drain],
     * which reads its frames as well as the still the session read.
     */
    suspend fun writeThrough() {
        combine(tagger.ready, signatures.observeVisionProgress(tagger.modelId).map { it.total }.distinctUntilChanged()) { ready, _ -> ready }
            .collect { ready ->
                if (!ready) return@collect
                val known = signatures.libraryPostsWithoutVision(tagger.modelId, WRITE_THROUGH_LIMIT)
                    .filterNot(tagger::plays)
                    .mapNotNull { post -> tagger.remembered(post.id)?.let { post.id to it } }.toMap()
                if (known.isNotEmpty()) tagger.store(known)
            }
    }

    private companion object {
        /** About four seconds of stills at phase 0's 450 ms each; playing posts read up to seven pictures each. */
        const val BATCH = 8
        const val WRITE_THROUGH_LIMIT = 64
    }
}
