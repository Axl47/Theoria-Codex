package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.theoriacodex.data.repository.MAX_FRAME_ATTEMPTS
import com.theoriacodex.data.repository.PictureVision
import com.theoriacodex.data.repository.PredictedTagCodec
import com.theoriacodex.data.repository.PREVIEW_RETRY_MS
import com.theoriacodex.data.repository.VisionCommit
import com.theoriacodex.data.repository.VisionFrames
import com.theoriacodex.data.repository.VisionProgress
import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PredictedTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomVisualSignatureRepository(
    private val database: TheoriaRoomDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : VisualSignatureRepository {
    private val dao = database.visualSignatureDao()

    override fun observePictureHashes(): Flow<Map<PostId, Long>> = dao.observeHashed().map { rows ->
        rows.mapNotNull { row ->
            val source = SourceKey.parse(row.source) ?: return@mapNotNull null
            PostId(source, row.sourcePostId) to row.phash
        }.toMap()
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun putHashes(hashes: Map<PostId, Long?>) {
        if (hashes.isEmpty()) return
        val now = clock()
        withContext(Dispatchers.IO) {
            database.withTransaction {
                hashes.forEach { (postId, hash) -> dao.put(postId.source.id, postId.sourcePostId, hash, now) }
            }
        }
    }

    override suspend fun failPreviews(postIds: Collection<PostId>) {
        if (postIds.isEmpty()) return
        val now = clock()
        withContext(Dispatchers.IO) {
            database.withTransaction {
                postIds.forEach { postId -> dao.failPreview(postId.source.id, postId.sourcePostId, now) }
            }
        }
    }

    override suspend fun libraryPostsWithoutSignature(limit: Int, skip: Int): List<Post> = withContext(Dispatchers.IO) {
        dao.libraryPostsWithoutSignature(limit, skip, clock() - PREVIEW_RETRY_MS).decoded()
    }

    @VisionCommit
    override suspend fun putVision(entries: List<PictureVision>) {
        if (entries.isEmpty()) return
        val now = clock()
        withContext(Dispatchers.IO) {
            database.withTransaction {
                for (entry in entries) {
                    dao.putVision(
                        entry.postId.source.id, entry.postId.sourcePostId, now, entry.modelId, entry.embedding,
                        entry.tags?.let(PredictedTagCodec::encode), entry.frames.ordinal,
                    )
                }
            }
        }
    }

    override suspend fun libraryPostsWithoutVision(modelId: String, limit: Int, skip: Int): List<Post> =
        withContext(Dispatchers.IO) { dao.libraryPostsWithoutVision(modelId, limit, skip, clock() - PREVIEW_RETRY_MS).decoded() }

    override suspend fun libraryPostsAwaitingFrames(modelId: String, limit: Int, skip: Int): List<Post> =
        withContext(Dispatchers.IO) { dao.libraryPostsAwaitingFrames(modelId, MAX_FRAME_ATTEMPTS, limit, skip).decoded() }

    @VisionCommit
    override suspend fun failFrames(postId: PostId, modelId: String, permanently: Boolean) {
        withContext(Dispatchers.IO) {
            dao.addFrameAttempts(postId.source.id, postId.sourcePostId, modelId, if (permanently) MAX_FRAME_ATTEMPTS else 1)
        }
    }

    @VisionCommit
    override suspend fun unwantFrames(postIds: List<PostId>, modelId: String) {
        if (postIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            database.withTransaction {
                for (id in postIds) dao.setFrameState(id.source.id, id.sourcePostId, modelId, VisionFrames.NONE.ordinal)
            }
        }
    }

    override fun observeVisionChanges(): Flow<Unit> = database.invalidationTracker.createFlow(SIGNATURES).map { }

    override suspend fun predictedTags(modelId: String): Map<PostId, List<PredictedTag>> {
        val rows = withContext(Dispatchers.IO) { dao.predictedTags(modelId) }
        return withContext(Dispatchers.Default) {
            rows.mapNotNull { row ->
                val source = SourceKey.parse(row.source) ?: return@mapNotNull null
                PostId(source, row.sourcePostId) to PredictedTagCodec.decode(row.predictedTags)
            }.toMap()
        }
    }

    override fun observeVisionProgress(modelId: String): Flow<VisionProgress> =
        dao.observeVisionProgress(modelId, MAX_FRAME_ATTEMPTS).map { VisionProgress(it.tagged, it.awaitingFrames, it.total) }.distinctUntilChanged()

    override fun observeLibraryTotal(): Flow<Int> =
        database.invalidationTracker.createFlow(*LIBRARY).map { dao.libraryTotal() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    @VisionCommit
    override suspend fun clearVision() {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                dao.clearVision()
                dao.deleteUnhashedRows()
            }
        }
    }

    private companion object {
        const val SIGNATURES = "post_visual_signatures"
        val LIBRARY = arrayOf("codex_items", "liked_posts")
    }

    private fun List<PostEntity>.decoded(): List<Post> =
        mapNotNull { entity -> runCatching { LocalPostPayloadCodec.decode(entity) }.getOrNull() }
}
