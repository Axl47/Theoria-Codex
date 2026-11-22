package com.theoriacodex.data.android.room

import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomVisualSignatureRepository(
    database: TheoriaRoomDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : VisualSignatureRepository {
    private val dao = database.visualSignatureDao()

    override fun observePictureHashes(): Flow<Map<PostId, Long>> = dao.observeHashed().map { rows ->
        rows.mapNotNull { row ->
            val source = SourceKey.parse(row.source) ?: return@mapNotNull null
            row.phash?.let { PostId(source, row.sourcePostId) to it }
        }.toMap()
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun put(postId: PostId, hash: Long?) {
        withContext(Dispatchers.IO) { dao.put(postId.source.id, postId.sourcePostId, hash, clock()) }
    }

    override suspend fun libraryPostsWithoutSignature(limit: Int): List<Post> = withContext(Dispatchers.IO) {
        dao.libraryPostsWithoutSignature(limit).mapNotNull { entity -> runCatching { LocalPostPayloadCodec.decode(entity) }.getOrNull() }
    }
}
