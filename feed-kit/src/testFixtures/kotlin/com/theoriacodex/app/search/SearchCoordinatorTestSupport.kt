package com.theoriacodex.app.search

import kotlin.coroutines.EmptyCoroutineContext
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.delay
import com.theoriacodex.domain.query.SearchTermLimit

class TestRegistry(adapters: List<SourceAdapter>) : SourceAdapterRegistry {
    private val bySource = adapters.associateBy(SourceAdapter::sourceKey)
    private val orchestrator = UnifiedSearchOrchestrator(bySource, providerContext = EmptyCoroutineContext)
    override fun availableSources(): Set<SourceKey> = bySource.keys
    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = bySource[sourceKey]
    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = orchestrator
}

class TestAdapter(
    override val sourceKey: SourceKey,
    searchTermLimit: SearchTermLimit? = null,
    /** Like the real Gelbooru preset, the Gelbooru stand-in matches only exactly spelled tags. */
    requiresExactTags: Boolean = sourceKey == SourceKey.GELBOORU,
) : SourceAdapter {
    var failure: Throwable? = null
    var autocompleteFailure: Throwable? = null
    var autocomplete: List<TagSuggestion> = emptyList()
    val autocompleteByPrefix = mutableMapOf<String, List<TagSuggestion>>()
    val autocompletePrefixes = mutableListOf<String>()
    var autocompleteResponseDelayMs = 0L
    val searchedTags = mutableListOf<List<String>>()
    val searchedQueries = mutableListOf<Query>()
    val searchedExclusions = mutableListOf<List<String>>()
    var resolvedPost: Post? = null
    var fixedPosts: List<Post>? = null
    var resolveFailure: Throwable? = null
    var resolveCalls = 0
    override val capabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = true,
        supportsExcludeTagsServerSide = true,
        searchTermLimit = searchTermLimit,
        requiresExactTags = requiresExactTags,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        failure?.let { throw it }
        searchedQueries += query
        searchedTags += query.includeTags
        searchedExclusions += query.excludeTags
        val tag = query.includeTags.firstOrNull().orEmpty()
        val index = if (pageToken == null) 0 else 1
        return Page(
            items = fixedPosts ?: listOf(post(sourceKey, "$tag-$index")),
            nextPageToken = "next".takeIf { pageToken == null },
        )
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = autocomplete.take(limit)

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        autocompleteFailure?.let { throw it }
        autocompletePrefixes += prefix
        if (autocompleteResponseDelayMs > 0L) delay(autocompleteResponseDelayMs)
        return autocompleteByPrefix[prefix]?.take(limit) ?: autocomplete.take(limit)
    }

    override suspend fun resolvePost(id: PostId): Post? {
        resolveCalls += 1
        resolveFailure?.let { throw it }
        return resolvedPost
    }
}

internal class FacetedAdapter(
    source: SourceKey,
    override val supportedSearchScopes: Set<FacetedSearchScope>,
    private val autocomplete: List<FacetedTagSuggestion>,
    private val featured: List<FacetedTagSuggestion>,
) : SourceAdapter by TestAdapter(source), FacetedSearchSourceAdapter {
    var failure: Throwable? = null

    override suspend fun autocompleteFaceted(
        prefix: String,
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion> {
        failure?.let { throw it }
        return autocomplete.filter { suggestion ->
            suggestion.text.startsWith(prefix, ignoreCase = true) &&
                (scope.isAll || suggestion.facet == scope.facet && suggestion.sourceNamespace == scope.sourceNamespace)
        }.take(limit)
    }

    override suspend fun featuredFacetedSuggestions(
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion> = featured.filter { suggestion ->
        suggestion.facet == scope.facet && suggestion.sourceNamespace == scope.sourceNamespace
    }.take(limit)
}

class RecordingTagStore : TagSuggestionStore {
    private val legacy = mutableMapOf<SourceKey, MutableList<TagSuggestion>>()
    private val trending = mutableMapOf<SourceKey, List<TagSuggestion>>()
    val faceted = mutableListOf<FacetedTagSuggestion>()

    override fun get(source: SourceKey, limit: Int): List<TagSuggestion> = legacy[source].orEmpty().take(limit)

    override fun put(source: SourceKey, suggestions: List<TagSuggestion>) {
        legacy.getOrPut(source) { mutableListOf() }.apply {
            suggestions.forEach { incoming ->
                removeAll { it.text == incoming.text }
                add(incoming)
            }
        }
    }

    override fun getTrending(source: SourceKey, limit: Int): List<TagSuggestion> =
        trending[source].orEmpty().take(limit)

    override fun replaceTrending(source: SourceKey, suggestions: List<TagSuggestion>) {
        trending[source] = suggestions
        put(source, suggestions)
    }

    override fun getFaceted(
        source: SourceKey,
        limit: Int,
        scope: FacetedSearchScope,
    ): List<FacetedTagSuggestion> = faceted.filter { suggestion ->
        scope.isAll || suggestion.facet == scope.facet && suggestion.sourceNamespace == scope.sourceNamespace
    }.take(limit)

    override fun putFaceted(source: SourceKey, suggestions: List<FacetedTagSuggestion>) {
        faceted += suggestions
    }
}

fun query(source: SourceKey, tag: String) = Query(
    mode = QueryMode.Source(source),
    includeTerms = listOf(SearchTerm(tag)),
    excludeTerms = emptyList(),
    sort = SortMode.NEWEST,
    dateRange = null,
    minScore = null,
)

fun unifiedQuery(tag: String) = query(SourceKey.PIXIV, tag).copy(mode = QueryMode.Unified)

fun post(source: SourceKey, id: String) = Post(
    id = PostId(source, id),
    preview = ImageRef("https://example.test/$id.jpg", null, "image/jpeg"),
    full = null,
    pageUrl = null,
    width = 100,
    height = 100,
    canonicalTags = emptyList(),
    rawTags = emptyList(),
    authorName = null,
    createdAtEpochMs = null,
)
