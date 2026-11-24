package com.theoriacodex.app.vision

import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class LibraryVisionBackfill(
    private val tagger: PictureTagger,
    private val signatures: VisualSignatureRepository,
    private val hot: () -> Boolean,
) {
    suspend fun drain(): Boolean {
        val unreachable = HashSet<PostId>()
        while (tagger.ready.value) {
            if (hot()) return true
            val batch = signatures.libraryPostsWithoutVision(tagger.modelId, unreachable.size + BATCH).filterNot { it.id in unreachable }
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

    private inner class FrameRun {
        private var unreachable = 0

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

    suspend fun writeThrough() {
        tagger.ready.first { it }
        settleStills()
        combine(tagger.ready, signatures.observeLibraryTotal()) { ready, _ -> ready }
            .collect { ready ->
                if (!ready) return@collect
                val known = signatures.libraryPostsWithoutVision(tagger.modelId, WRITE_THROUGH_LIMIT)
                    .mapNotNull { post -> tagger.remembered(post.id)?.let { post to it } }.toMap()
                if (known.isNotEmpty()) tagger.store(known)
            }
    }

    private suspend fun settleStills() {
        var playing = 0
        while (tagger.ready.value) {
            val awaiting = signatures.libraryPostsAwaitingFrames(tagger.modelId, SETTLE_BATCH, skip = playing)
            if (awaiting.isEmpty()) return
            playing += tagger.playing(awaiting).size
        }
    }

    private companion object {
        const val BATCH = 8
        const val WRITE_THROUGH_LIMIT = 64
        const val SETTLE_BATCH = 256

        const val UNREACHABLE_STREAK = 3
    }
}
