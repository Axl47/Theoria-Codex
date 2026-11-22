package com.theoriacodex.app.vision

import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.PostId
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Tags the pictures of liked and saved posts that the current model has not analysed, and stores what it found beside
 * each post's hash. The app's charging-only background job runs [drain]; [writeThrough] runs while the app does.
 */
class LibraryVisionBackfill(
    private val tagger: PictureTagger,
    private val signatures: VisualSignatureRepository,
    /** Whether the device is warm enough that indexing should wait (thermal status moderate or worse). */
    private val hot: () -> Boolean,
) {
    /**
     * Analyses a batch at a time and rests as long as the batch took, so the CPU works half the time at most. Stops when
     * done, when the model goes or would not load, when the device runs [hot], or after [UNREACHABLE_IN_A_ROW] previews
     * in a row failed to load (offline, say). Returns whether pictures are left that a later run can read; previews that
     * failed to load are skipped for this run and tried again by the next.
     */
    suspend fun drain(): Boolean {
        val unreachable = HashSet<PostId>()
        var inARow = 0
        while (tagger.ready.value) {
            if (hot()) return true
            val batch = signatures.libraryPostsWithoutVision(tagger.modelId, unreachable.size + BATCH).filterNot { it.id in unreachable }
            if (batch.isEmpty()) return false
            val started = TimeSource.Monotonic.markNow()
            val results = batch.associate { post -> post.id to tagger.analyze(post, remember = false) }
            if (PictureResult.Unavailable in results.values) return false
            val failed = results.filterValues { it == PictureResult.Unreachable }.keys
            inARow = if (failed.size == results.size) inARow + failed.size else 0
            unreachable += failed
            if (!tagger.store(results) || inARow >= UNREACHABLE_IN_A_ROW) return false
            delay(started.elapsedNow())
        }
        return false
    }

    /**
     * Stores what this session already analysed for posts kept since (a For You card liked after it was vetted, say),
     * whenever the Library gains a post, so those pictures are never read twice.
     */
    suspend fun writeThrough() {
        combine(tagger.ready, signatures.observeVisionProgress(tagger.modelId).map { it.total }.distinctUntilChanged()) { ready, _ -> ready }
            .collect { ready ->
                if (!ready) return@collect
                val known = signatures.libraryPostsWithoutVision(tagger.modelId, WRITE_THROUGH_LIMIT)
                    .mapNotNull { post -> tagger.remembered(post.id)?.let { post.id to it } }.toMap()
                if (known.isNotEmpty()) tagger.store(known)
            }
    }

    private companion object {
        /** About four seconds of pictures at phase 0's 450 ms each. */
        const val BATCH = 8
        const val WRITE_THROUGH_LIMIT = 64
        const val UNREACHABLE_IN_A_ROW = 32
    }
}
