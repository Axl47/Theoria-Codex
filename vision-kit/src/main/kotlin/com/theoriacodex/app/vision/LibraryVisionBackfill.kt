package com.theoriacodex.app.vision

import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Tags the pictures of liked and saved posts that the current model has not analysed, and stores what it found beside
 * each post's hash. The app's charging-only background job runs [drain]; [writeThrough] runs while the app does. A post
 * that plays is first stored from its still like any other, and [drainFrames], in a job that runs only while the device
 * is idle, later replaces that with its still and frames together.
 */
class LibraryVisionBackfill(
    private val tagger: PictureTagger,
    private val signatures: VisualSignatureRepository,
    /** Whether the device is warm enough that indexing should wait (thermal status moderate or worse). */
    private val hot: () -> Boolean,
) {
    /**
     * Analyses a batch at a time and rests as long as the batch took, so the CPU works half the time at most. Stops when
     * done, when the model goes or would not load, or when the device runs [hot]; losing the network stops the job
     * itself. Returns whether pictures are left that a later run can read, which includes a model that would not load
     * but is still installed (memory pressure, say) and previews that failed to load, which are skipped for the rest of
     * this run.
     */
    suspend fun drain(): Boolean {
        val unreachable = HashSet<PostId>()
        while (tagger.ready.value) {
            if (hot()) return true
            val batch = signatures.libraryPostsWithoutVision(tagger.modelId, unreachable.size + BATCH).filterNot { it.id in unreachable }
            // Only previews that failed to load this run are left: a later run tries them again.
            if (batch.isEmpty()) return unreachable.isNotEmpty()
            val started = TimeSource.Monotonic.markNow()
            val results = batch.associateWith { post -> tagger.analyze(post, remember = false) }
            if (PictureResult.Unavailable in results.values) return tagger.ready.value
            unreachable += results.filterValues { it == PictureResult.Unreachable }.keys.map { it.id }
            if (!tagger.store(results)) return false
            delay(started.elapsedNow())
        }
        return false
    }

    /**
     * Replaces the still analyses of playing posts with their still and frames, one post at a time, each stored as it
     * finishes, resting as long as each took. Stops when done, when the model goes or would not load, or when the
     * device runs [hot]; the job stops it when the device leaves idle, losing only the post under way. A post whose
     * frames cannot be read keeps its still analysis, and is tried once a run, on later runs (the job is scheduled at
     * every app start), only while another try may pass and a few times at most, so a source is not asked every night.
     * Posts failing to be reached one after another point at the network rather than at them: only the first is counted
     * against its post, and after [UNREACHABLE_STREAK] the run stops. Returns whether this run was cut short (heat, a
     * model that would not load, the network) and so should run again soon.
     */
    suspend fun drainFrames(): Boolean {
        val tried = HashSet<PostId>()
        val run = FrameRun()
        while (tagger.ready.value) {
            val awaiting = signatures.libraryPostsAwaitingFrames(tagger.modelId, tried.size + BATCH).filterNot { it.id in tried }
            if (awaiting.isEmpty()) return false
            awaiting.mapTo(tried) { it.id }
            for (post in tagger.playing(awaiting)) run.upgrade(post)?.let { return it }
        }
        return false
    }

    /** One [drainFrames] run's walk through its posts, counting those in a row that could not be reached. */
    private inner class FrameRun {
        private var unreachable = 0

        /** Reads and stores one post's frames, then rests as long as that took; returns null to go on, or what the run returns. */
        suspend fun upgrade(post: Post): Boolean? {
            if (hot()) return true
            val started = TimeSource.Monotonic.markNow()
            val result = tagger.analyzeFrames(post)
            if (result == PictureResult.Unavailable) return tagger.ready.value
            unreachable = if (result == PictureResult.Unreachable) unreachable + 1 else 0
            if (unreachable <= 1 && !tagger.storeFrames(post.id, result)) return false
            if (unreachable >= UNREACHABLE_STREAK) return true
            delay(started.elapsedNow())
            return null
        }
    }

    /**
     * Stores what this session already analysed for posts kept since (a For You card liked after it was vetted, say),
     * whenever the Library gains a post, so those pictures are never read twice. First, once the model is ready, it sets
     * aside the frames of posts waiting on them that do not play ([settleStills]).
     */
    suspend fun writeThrough() {
        tagger.ready.first { it }
        settleStills()
        combine(tagger.ready, signatures.observeVisionProgress(tagger.modelId).map { it.total }.distinctUntilChanged()) { ready, _ -> ready }
            .collect { ready ->
                if (!ready) return@collect
                val known = signatures.libraryPostsWithoutVision(tagger.modelId, WRITE_THROUGH_LIMIT)
                    .mapNotNull { post -> tagger.remembered(post.id)?.let { post to it } }.toMap()
                if (known.isNotEmpty()) tagger.store(known)
            }
    }

    /**
     * Marks every analysis awaiting frames whose post does not play as all its post offers. Whether a post plays is known
     * on the phone, so a still image never waits for the idle-only [drainFrames] to count as tagged; the migration that
     * added frames marked every earlier analysis as awaiting them.
     */
    private suspend fun settleStills() {
        // The posts that play stay awaiting, ahead of the next batch; the others leave, so each post is read once.
        var playing = 0
        while (tagger.ready.value) {
            val awaiting = signatures.libraryPostsAwaitingFrames(tagger.modelId, SETTLE_BATCH, skip = playing)
            if (awaiting.isEmpty()) return
            playing += tagger.playing(awaiting).size
        }
    }

    private companion object {
        /** About four seconds of pictures at phase 0's 450 ms each. */
        const val BATCH = 8
        const val WRITE_THROUGH_LIMIT = 64
        const val SETTLE_BATCH = 256

        /** Posts in a row that could not be reached after which a frame run stops. */
        const val UNREACHABLE_STREAK = 3
    }
}
