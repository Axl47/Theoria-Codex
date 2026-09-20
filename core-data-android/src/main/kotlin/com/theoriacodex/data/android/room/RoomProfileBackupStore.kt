package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.theoriacodex.data.repository.BackupLibrary
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.ProfileBackupStore
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.storage.RecentSearchPayloadCodec
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey

/** Logical snapshots and additive imports share the existing Room transaction boundary. */
class RoomProfileBackupStore(
    private val database: TheoriaRoomDatabase,
    private val gson: Gson = Gson(),
) : ProfileBackupStore {
    private val dao = database.codexLikesDao()
    private val recents = database.recentsDao()
    private val postCodec = LocalPostPayloadCodec(gson)

    override suspend fun snapshot(includeRecents: Boolean): BackupLibrary = database.withTransaction {
        val codices = dao.codices().map { row ->
            Codex(row.codexId, row.name, row.createdAtEpochMs,
                dao.automaticTagsForCodex(row.codexId).map {
                    CodexAutomaticTag(SourceKey.valueOf(it.source), it.tagDisplay, it.groupIndex)
                }.sortedWith(compareBy<CodexAutomaticTag> { it.source.ordinal }.thenBy { it.groupIndex }
                    .thenBy { sourceTagKey(it.source, it.tag) }))
        }
        val items = dao.codexItems().map {
            CodexItem(it.codexId, PostId(SourceKey.valueOf(it.source), it.sourcePostId), it.savedAtEpochMs)
        }
        val likes = dao.allLikes().map { row ->
            LikedPost(row.profileId, PostId(SourceKey.valueOf(row.source), row.sourcePostId), row.likedAtEpochMs,
                JsonParser.parseString(row.tagsJson).asJsonArray.map { it.asString })
        }
        val watchedRows = if (includeRecents) recents.watched().filter { it.section != RecentPostSection.FYP.name } else emptyList()
        val referencedIds = items.map { it.postId }.toSet() + likes.map { it.postId } + watchedRows.map {
            PostId(SourceKey.valueOf(it.source), it.sourcePostId)
        }
        val likesById = likes.associateBy { it.postId }
        val posts = referencedIds.map { id ->
            dao.post(id.source.name, id.sourcePostId)?.let(postCodec::decode)
                ?: legacyLikedPost(requireNotNull(likesById[id]) { "Saved post payload is missing" })
        }
        val postsById = posts.associateBy { it.id }
        BackupLibrary(codices, items, posts, likes,
            watchedRows.map {
                RecentPostEntry(postsById.getValue(PostId(SourceKey.valueOf(it.source), it.sourcePostId)),
                    it.viewedAtEpochMs, ViewerStreamSource.valueOf(it.origin), it.originQueryHash,
                    RecentPostSection.valueOf(it.section), it.maxViewedMediaNumber)
            },
            if (includeRecents) recents.searches().map { row ->
                val payload = RecentSearchPayloadCodec.decodeJson(row.queryPayloadJson, gson)
                RecentSearchEntry(payload.query, row.queryHash, row.searchedAtEpochMs,
                    payload.kind, payload.sources, payload.sourceTags)
            } else emptyList(),
        )
    }

    override suspend fun merge(library: BackupLibrary) {
        database.withTransaction {
            // Existing shared payloads can contain useful offline bytes and richer resolved media.
            library.posts.forEach { post ->
                dao.insertPost(PostEntity(post.id.source.name, post.id.sourcePostId, postCodec.encode(post)))
            }
            var displayOrder = dao.codices().size
            library.codices.forEach { codex ->
                if (dao.insertCodex(CodexEntity(codex.codexId, codex.name, codex.createdAtEpochMs, displayOrder)) != -1L) {
                    displayOrder += 1
                    dao.insertAutomaticTags(codex.automaticTags.map {
                        CodexAutomaticTagEntity(codex.codexId, it.source.name, sourceTagKey(it.source, it.tag), it.tag, it.groupIndex)
                    })
                }
            }
            library.items.forEach { item ->
                dao.insertCodexItem(CodexItemEntity(item.codexId, item.postId.source.name,
                    item.postId.sourcePostId, item.savedAtEpochMs))
            }
            library.likes.forEach { like ->
                val systemCodexId = ProfileLibraryIds.likes(like.profileId)
                if (dao.insertCodex(CodexEntity(systemCodexId, "Likes", like.likedAtEpochMs, displayOrder)) != -1L) {
                    displayOrder += 1
                }
                dao.insertCodexItem(CodexItemEntity(systemCodexId, like.postId.source.name,
                    like.postId.sourcePostId, like.likedAtEpochMs))
                dao.insertLike(LikedPostEntity(like.profileId, like.postId.source.name, like.postId.sourcePostId,
                    like.likedAtEpochMs, gson.toJson(like.tags)))
            }
            mergeRecents(library)
        }
    }

    private fun mergeRecents(library: BackupLibrary) {
        library.watched.asReversed().forEach { entry ->
            val id = entry.post.id
            val existing = recents.watched(id.source.name, id.sourcePostId, entry.section.name)
            if (existing == null || entry.viewedAtEpochMs > existing.viewedAtEpochMs) {
                recents.upsertWatched(RecentWatchedEntity(id.source.name, id.sourcePostId, entry.section.name,
                    entry.viewedAtEpochMs, recents.nextWatchedSequence(), entry.origin.name, entry.originQueryHash,
                    maxOf(entry.maxViewedMediaNumber, existing?.maxViewedMediaNumber ?: 1)))
            } else {
                recents.updateWatchedMediaProgress(id.source.name, id.sourcePostId, entry.section.name, entry.maxViewedMediaNumber)
            }
        }
        library.searches.asReversed().forEach { entry ->
            val existing = recents.search(entry.queryHash)
            if (existing == null || entry.searchedAtEpochMs > existing.searchedAtEpochMs) {
                recents.upsertSearch(RecentSearchEntity(entry.queryHash, RecentSearchPayloadCodec.encodeJson(entry, gson),
                    entry.searchedAtEpochMs, recents.nextSearchSequence()))
            }
        }
        recents.trimWatched(DEFAULT_RECENT_WATCHED_LIMIT)
        recents.trimSearches(DEFAULT_RECENT_SEARCH_LIMIT)
    }
}

/** Pre-Room Likes could persist an identity and tags without any saved Post snapshot. */
private fun legacyLikedPost(like: LikedPost) = Post(
    id = like.postId,
    preview = ImageRef(null, null, null),
    full = null,
    pageUrl = null,
    width = null,
    height = null,
    canonicalTags = like.tags,
    rawTags = like.tags,
    authorName = null,
    createdAtEpochMs = null,
)
