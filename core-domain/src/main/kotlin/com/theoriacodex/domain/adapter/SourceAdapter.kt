package com.theoriacodex.domain.adapter

import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTagType
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.SearchTermLimit

interface SourceAdapter {
    val sourceKey: SourceKey
    val capabilities: SourceCapabilities

    suspend fun search(query: Query, pageToken: String?): Page<Post>
    suspend fun trendingTags(limit: Int): List<TagSuggestion>
    suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion>
    suspend fun resolvePost(id: PostId): Post?
}

interface SearchMetadataSourceAdapter {
    suspend fun resolveSearchMetadata(post: Post): Post?
}

interface CreatorPostsSourceAdapter {
    suspend fun searchCreatorPosts(
        creator: CreatorProfile,
        pageToken: String?,
    ): Page<Post>
}

interface AccountProfileSourceAdapter {
    fun accountFeeds(creator: CreatorProfile): List<AccountFeed>

    suspend fun accountDetails(creator: CreatorProfile): AccountDetails?

    suspend fun accountPosts(creator: CreatorProfile, feed: AccountFeed, pageToken: String?): Page<Post>
}

interface FileHashLookupSourceAdapter {
    suspend fun findByFileHash(md5: String): Post?
}

const val MAX_RELATED_POST_CANDIDATES = 30

interface RelatedPostsSourceAdapter {
    suspend fun relatedPosts(seed: PostId, limit: Int): List<Post>
}

interface CommentsSourceAdapter {
    suspend fun comments(post: Post, pageToken: String?, parent: PostComment? = null): CommentPage

    fun nativeCommentAnchor(commentId: String): String? = null
}

interface MediaRecoverySourceAdapter {
    suspend fun recoverPostMedia(
        post: Post,
        failedMedia: ImageRef,
    ): Post?
}

interface DurationMetadataSourceAdapter {
    suspend fun resolveDurationMetadata(post: Post): DurationMetadataSourceResult
}

sealed interface DurationMetadataSourceResult {
    data class Known(val durationMs: Long) : DurationMetadataSourceResult {
        init {
            require(durationMs > 0L) { "Provider duration must be positive" }
        }
    }

    data class AuthoritativeMedia(val media: ImageRef) : DurationMetadataSourceResult
    data object Unsupported : DurationMetadataSourceResult
    data object RetryableFailure : DurationMetadataSourceResult
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

    val tagType: SearchTagType?
        get() = facet?.let { SearchTagType.of(it, sourceNamespace) }

    fun accepts(term: SearchTerm): Boolean = isAll || tagType == term.tagType

    companion object {
        val All = FacetedSearchScope()
    }
}

data class FacetedTagSuggestion(
    val text: String,
    val facet: SearchFacet,
    val sourceNamespace: String? = null,
    val count: Int? = null,
    val alternateText: String? = null,
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

    suspend fun featuredFacetedSuggestions(
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion> = emptyList()
}

interface TagCountLookupSourceAdapter {
    suspend fun fetchTagCounts(tags: List<String>): Map<String, Int>

    suspend fun fetchTagCountsIncrementally(tags: List<String>, onCounts: (Map<String, Int>) -> Unit) {
        onCounts(fetchTagCounts(tags))
    }
}

data class Page<T>(
    val items: List<T>,
    val nextPageToken: String?,
)

data class TagSuggestion(
    val text: String,
    val type: String?,
    val count: Int?,
    val alternateText: String? = null,
)

data class SourceCapabilities(
    val supportsSortNewest: Boolean,
    val supportsSortPopular: Boolean,
    val supportsSortTop: Boolean,
    val supportsSortRandom: Boolean,
    val supportsExcludeTagsServerSide: Boolean,
    val supportsGroupedIncludeTagsServerSide: Boolean = false,
    val searchTermLimit: SearchTermLimit? = null,
    val requiresExactTags: Boolean = false,
    val animatedTerm: AnimatedSearchTerm? = null,
)

data class AnimatedSearchTerm(
    val term: SearchTerm,
    val overLimit: SearchTerm? = null,
)
