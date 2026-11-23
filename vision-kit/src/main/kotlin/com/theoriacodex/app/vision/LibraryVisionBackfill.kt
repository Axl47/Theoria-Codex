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
    /**
     * Whether a playing post's frames may be decoded now: none of the app is on screen and no player is leased. A frame
     * decoder can take a hardware codec a visible card needs, so frames are read only then.
     */
    private val quiet: suspend () -> Boolean,
) {
    /**
     * Analyses a batch at a time and rests as long as the batch took, so the CPU works half the time at most. Stops when
     * done, when the model goes or would not load, or when the device runs [hot]; losing the network stops the job
     * itself. Returns whether pictures are left that a later run can read, which includes a model that would not load
     * but is still installed (memory pressure, say) and previews that failed to load, which are skipped for the rest of
     * this run. Playing posts read frames too ([PictureTagger.analyzeForLibrary]), but only while the app is [quiet]; the
     * rest wait for a later run.
     */
    suspend fun drain(): Boolean {
        val later = HashSet<PostId>()
        while (tagger.ready.value) {
            if (hot()) return true
            val batch = signatures.libraryPostsWithoutVision(tagger.modelId, later.size + BATCH).filterNot { it.id in later }
            // Only posts that could not be read now are left: a later run tries them again.
            if (batch.isEmpty()) return later.isNotEmpty()
            val started = TimeSource.Monotonic.markNow()
            val results = LinkedHashMap<PostId, PictureResult>()
            for (post in batch) {
                // Checked before each playing post, so frames stop between posts once the app is in use.
                results[post.id] = if (tagger.plays(post) && !quiet()) PictureResult.Unreachable else tagger.analyzeForLibrary(post)
            }
            if (PictureResult.Unavailable in results.values) return tagger.ready.value
            later += results.filterValues { it == PictureResult.Unreachable }.keys
            if (!tagger.store(results)) return false
            delay(started.elapsedNow())
        }
        return false
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
