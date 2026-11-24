package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.theoriacodex.data.repository.FOR_YOU_VERDICT_LIMIT
import com.theoriacodex.data.repository.ForYouVerdict
import com.theoriacodex.data.repository.ForYouVerdictKind
import com.theoriacodex.data.repository.ForYouVerdictRepository
import com.theoriacodex.data.repository.ProfileLibraryWriteGuard
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomForYouVerdictRepository(
    private val database: TheoriaRoomDatabase,
    private val writeGuard: ProfileLibraryWriteGuard = ProfileLibraryWriteGuard(),
    private val limit: Int = FOR_YOU_VERDICT_LIMIT,
) : ForYouVerdictRepository {
    private val dao = database.forYouVerdictDao()

    override suspend fun record(verdict: ForYouVerdict) {
        database.withTransaction {
            writeGuard.requireProfileWritable(verdict.profileId)
            dao.upsert(
                ForYouVerdictEntity(
                    verdict.profileId, verdict.postId.source.id, verdict.postId.sourcePostId, verdict.kind.name,
                    verdict.decidedAtEpochMs, verdict.previewUrl,
                ),
            )
            dao.trimToLimit(verdict.profileId, limit)
        }
    }

    override suspend fun remove(verdict: ForYouVerdict) {
        withContext(Dispatchers.IO) {
            dao.delete(verdict.profileId, verdict.postId.source.id, verdict.postId.sourcePostId, verdict.decidedAtEpochMs)
        }
    }

    override suspend fun verdicts(profileId: String): List<ForYouVerdict> = withContext(Dispatchers.IO) {
        dao.forProfile(profileId).mapNotNull { row ->
            val source = SourceKey.parse(row.source) ?: return@mapNotNull null
            ForYouVerdict(
                row.profileId, PostId(source, row.sourcePostId), ForYouVerdictKind.valueOf(row.decision),
                row.decidedAtEpochMs, row.previewUrl,
            )
        }
    }
}
