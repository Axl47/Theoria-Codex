package com.theoriacodex.domain.adapter

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey

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

/**
 * Optional source-owned recovery for media URLs whose provider configuration can change after a
 * post was loaded. The adapter receives the original post and exact failed media reference so it
 * can preserve source identity without teaching the app layer provider URL rules.
 */
interface MediaRecoverySourceAdapter {
    suspend fun recoverPostMedia(
        post: Post,
        failedMedia: ImageRef,
    ): Post?
}

data class FacetedSearchScope(
    val facet: SearchFacet? = null,
    val sourceNamespace: String? = null,
) {
    init {
        require(facet != null || sourceNamespace == null) {
            "A source namespace requires a search facet"
        }
    }

    val isAll: Boolean
        get() = facet == null

    companion object {
        val All = FacetedSearchScope()
    }
}

data class FacetedTagSuggestion(
    val text: String,
    val facet: SearchFacet,
    val sourceNamespace: String? = null,
    val count: Int? = null,
) {
    fun toSearchTerm(): SearchTerm {
        return SearchTerm(
            value = text,
            facet = facet,
            sourceNamespace = sourceNamespace,
        )
    }
}

interface FacetedSearchSourceAdapter {
    val supportedSearchScopes: Set<FacetedSearchScope>

    val supportedSearchFacets: Set<SearchFacet>
        get() = supportedSearchScopes.mapNotNullTo(linkedSetOf(), FacetedSearchScope::facet)

    suspend fun autocompleteFaceted(
        prefix: String,
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion>

    /** Source-owned values that make a selected facet discoverable before the user types. */
    suspend fun featuredFacetedSuggestions(
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion> = emptyList()
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
