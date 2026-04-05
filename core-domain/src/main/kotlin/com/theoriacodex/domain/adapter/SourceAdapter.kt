package com.theoriacodex.domain.adapter

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.CreatorProfile

interface SourceAdapter {
    val sourceKey: SourceKey
    val capabilities: SourceCapabilities

    suspend fun search(query: Query, pageToken: String?): Page<Post>
    suspend fun trendingTags(limit: Int): List<TagSuggestion>
    suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion>
    suspend fun quickQuery(kind: QuickQueryKind): Query
    suspend fun resolvePost(id: PostId): Post?
}

interface CreatorPostsSourceAdapter {
    suspend fun searchCreatorPosts(
        creator: CreatorProfile,
        pageToken: String?,
    ): Page<Post>
}

interface TagCountLookupSourceAdapter {
    suspend fun fetchTagCounts(tags: List<String>): Map<String, Int>
}

data class Page<T>(
    val items: List<T>,
    val nextPageToken: String?,
)

data class TagSuggestion(
    val text: String,
    val type: String?,
    val count: Int?,
)

data class SourceCapabilities(
    val supportsSortNewest: Boolean,
    val supportsSortPopular: Boolean,
    val supportsSortTop: Boolean,
    val supportsSortRandom: Boolean,
    val supportsExcludeTagsServerSide: Boolean,
    val supportsDateRangeServerSide: Boolean,
    val supportsMinScoreServerSide: Boolean,
    val requiresCredentials: Boolean,
)

enum class QuickQueryKind {
    POPULAR_TODAY,
    TOP_7D,
    TOP_30D,
    NEWEST,
    RANDOM,
}
