package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.theoriacodex.data.repository.BackupLibrary
import com.theoriacodex.data.repository.BackupSeries
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.ProfileBackupStore
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
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
) : ProfileBackupStore {
    private val dao = database.codexLikesDao()
    private val recents = database.recentsDao()
    private val recentsWriter = RecentsWriter(recents, SharedPosts(dao))
    private val series = database.seriesDao()

    override suspend fun snapshot(includeRecents: Boolean): BackupLibrary = database.withTransaction {
        val codices = dao.codices().map { row ->
            Codex(row.codexId, row.name, row.createdAtEpochMs,
                dao.automaticTagsForCodex(row.codexId).map {
                    CodexAutomaticTag(SourceKey.parseOrThrow(it.source), it.tagDisplay, it.groupIndex)
                }.sortedWith(compareBy<CodexAutomaticTag> { it.source }.thenBy { it.groupIndex }
                    .thenBy { sourceTagKey(it.source, it.tag) }))
        }
        val items = dao.codexItems().map {
            CodexItem(it.codexId, PostId(SourceKey.parseOrThrow(it.source), it.sourcePostId), it.savedAtEpochMs)
        }
        val likes = dao.allLikes().map { row ->
            LikedPost(row.profileId, PostId(SourceKey.parseOrThrow(row.source), row.sourcePostId), row.likedAtEpochMs,
                LikedTagsCodec.decode(row.tagsJson))
        }
        val watchedRows = if (includeRecents) recents.watched() else emptyList()
        val referencedIds = items.map { it.postId }.toSet() + likes.map { it.postId } + watchedRows.map {
            PostId(SourceKey.parseOrThrow(it.source), it.sourcePostId)
        }
        val likesById = likes.associateBy { it.postId }
        val posts = referencedIds.map { id ->
            dao.post(id.source.id, id.sourcePostId)?.let(LocalPostPayloadCodec::decode)
                ?: legacyLikedPost(requireNotNull(likesById[id]) { "Saved post payload is missing" })
        }
        val postsById = posts.associateBy { it.id }
        BackupLibrary(codices, items, posts, likes,
            watchedRows.map {
                RecentPostEntry(postsById.getValue(PostId(SourceKey.parseOrThrow(it.source), it.sourcePostId)),
                    it.viewedAtEpochMs, ViewerStreamSource.valueOf(it.origin), it.originQueryHash,
                    RecentPostSection.valueOf(it.section), it.maxViewedMediaNumber)
            },
            if (includeRecents) recents.searches().map(RecentSearchEntity::toDomain) else emptyList(),
            // Entries the source stopped listing are kept too: one that returns is then as the user left it.
            series.keptSeries().map { row ->
                BackupSeries(row.toStored(), series.allEntries(row.source, row.address).map(SeriesEntryEntity::toStored))
            },
        )
    }

    override suspend fun merge(library: BackupLibrary): Int =
        database.withTransaction {
            var collectionsAdded = 0
            // Existing shared payloads can contain useful offline bytes and richer resolved media.
            library.posts.forEach { post ->
                dao.insertPost(PostEntity(post.id.source.id, post.id.sourcePostId, LocalPostPayloadCodec.encode(post)))
            }
            var displayOrder = dao.codices().size
            library.codices.forEach { codex ->
                if (dao.insertCodex(CodexEntity(codex.codexId, codex.name, codex.createdAtEpochMs, displayOrder)) != -1L) {
                    displayOrder += 1
                    collectionsAdded += 1
                    dao.insertAutomaticTags(codex.automaticTags.map { it.toEntity(codex.codexId) })
                }
            }
            library.items.forEach { item -> dao.insertCodexItem(item.toEntity()) }
            library.likes.forEach { like ->
                val systemCodexId = ProfileLibraryIds.likes(like.profileId)
                if (dao.insertCodex(CodexEntity(systemCodexId, "Likes", like.likedAtEpochMs, displayOrder)) != -1L) {
                    displayOrder += 1
                }
                dao.insertCodexItem(CodexItemEntity(systemCodexId, like.postId.source.id,
                    like.postId.sourcePostId, like.likedAtEpochMs))
                dao.insertLike(like.toEntity())
            }
            mergeRecents(library)
            library.series.forEach(::mergeSeries)
            collectionsAdded
        }

    /**
     * Adds a backed-up series to what the device holds. An entry on both sides takes the further progress. When the
     * device already has entries for the series, they are the source's current list, so an entry only the backup has
     * arrives as one the source no longer lists: kept with its progress, but neither counted as unread nor announced
     * as new, and listed again as it was if the source brings it back. When that entry is the Continue point, the
     * series page offers the nearest listed chapter until its next refresh lists the entry again.
     */
    private fun mergeSeries(backup: BackupSeries) {
        val stored = backup.series
        val id = stored.series.id
        if (series.insertSeries(stored.toEntity()) == -1L) {
            series.mergeRestoredSeries(id.source.id, id.address, stored.followed, stored.readingMode?.name, stored.lastOpenedEntry, stored.downloadNewChapters)
        }
        val onDevice = series.allEntries(id.source.id, id.address).mapTo(HashSet()) { it.address }
        val (known, missing) = backup.entries.partition { it.entry.address in onDevice }
        known.forEach { series.mergeRestoredEntry(id.source.id, id.address, it.entry.address, it.read, it.lastPage, it.pageCount) }
        series.insertEntries(missing.map { (if (onDevice.isEmpty()) it else it.copy(sourceOrder = NOT_LISTED)).toEntity(id) })
    }

    private fun mergeRecents(library: BackupLibrary) {
        library.watched.asReversed().forEach { entry ->
            val id = entry.post.id
            val existing = recents.watched(id.source.id, id.sourcePostId, entry.section.name)
            if (existing == null || entry.viewedAtEpochMs > existing.viewedAtEpochMs) {
                recentsWriter.upsertWatched(id, entry.section.name, entry.viewedAtEpochMs, entry.origin.name,
                    entry.originQueryHash, maxOf(entry.maxViewedMediaNumber, existing?.maxViewedMediaNumber ?: 1))
            } else {
                recents.raiseWatchedMediaProgress(id.source.id, id.sourcePostId, entry.section.name, entry.maxViewedMediaNumber)
            }
        }
        library.searches.asReversed().forEach { entry ->
            val existing = recents.search(entry.queryHash)
            if (existing == null || entry.searchedAtEpochMs > existing.searchedAtEpochMs) {
                recentsWriter.upsertSearch(entry.queryHash, RecentSearchPayloadCodec.encodeJson(entry), entry.searchedAtEpochMs)
            }
        }
        // Trimming releases posts only the trimmed rows held, as Recents itself does.
        recentsWriter.trimWatched(DEFAULT_RECENT_WATCHED_LIMIT)
        recentsWriter.trimSearches(DEFAULT_RECENT_SEARCH_LIMIT)
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
