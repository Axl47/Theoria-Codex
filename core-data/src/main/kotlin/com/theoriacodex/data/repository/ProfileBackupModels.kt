package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post

/** A logical transaction snapshot, independent of Room's tables and database version. */
data class BackupLibrary(
    val codices: List<Codex>,
    val items: List<CodexItem>,
    val posts: List<Post>,
    val likes: List<LikedPost>,
    val watched: List<RecentPostEntry> = emptyList(),
    val searches: List<RecentSearchEntry> = emptyList(),
)

interface ProfileBackupStore {
    suspend fun snapshot(includeRecents: Boolean): BackupLibrary
    /** Imports the entire library atomically, keeping existing membership and post payloads. */
    suspend fun merge(library: BackupLibrary)
}

data class ProfileBackup(
    val createdAtEpochMs: Long,
    val settings: AppSettings,
    val library: BackupLibrary,
    val savedSearches: List<SavedSearchEntry> = emptyList(),
    val readingPositions: List<ReadingPosition> = emptyList(),
) {
    fun preview() = ProfileBackupPreview(
        createdAtEpochMs = createdAtEpochMs,
        profileNames = settings.recommendationProfiles.map(RecommendationProfile::name),
        collectionCount = library.codices.size,
        savedPostCount = library.items.map { it.postId }.distinct().size,
        likeCount = library.likes.size,
        savedSearchCount = savedSearches.size,
        recentCount = library.watched.size + library.searches.size,
        followedCreatorCount = settings.followedCreators.size,
    )
}

data class ProfileBackupPreview(
    val createdAtEpochMs: Long,
    val profileNames: List<String>,
    val collectionCount: Int,
    val savedPostCount: Int,
    val likeCount: Int,
    val savedSearchCount: Int,
    val recentCount: Int,
    val followedCreatorCount: Int,
)

data class ProfileRestoreResult(val profilesAdded: Int, val collectionsAdded: Int)
