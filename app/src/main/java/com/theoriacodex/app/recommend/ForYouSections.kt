package com.theoriacodex.app.recommend

import com.theoriacodex.app.codex.CodexSaveRelevance
import com.theoriacodex.app.search.TagCountCoordinator
import com.theoriacodex.app.search.TagSuggestionStore
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchRepository
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.RecommendationTagNormalization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Each preview retains the exact first page and continuation opened by its full feed. */
data class ForYouSection(
    val id: String,
    val title: String,
    val feed: ForYouFeedResult,
    val savedSearchId: String? = null,
    val discovery: Boolean = false,
) {
    val pinned: Boolean get() = savedSearchId != null
}

internal class ForYouSectionsLoader(
    registry: SourceAdapterRegistry,
    store: TagSuggestionStore,
    private val likes: LikesRepository,
    private val savedSearches: SavedSearchRepository,
    private val execute: suspend (ForYouFeedRequest, Map<SourceKey, List<String>>, Int) -> ForYouFeedResult,
) {
    private val counts = TagCountCoordinator(registry, store, { _, tag -> tag })

    suspend fun load(request: ForYouFeedRequest): List<ForYouSection> = withContext(Dispatchers.Default) {
        val sources = request.availableSources.filter { request.selectedSource == null || it == request.selectedSource }
        val liked = likes.observeLikes(request.settings.activeProfileId).first()
        val blocked = request.settings.forYouBlacklistByProfile[request.settings.activeProfileId].orEmpty()
        val pinned = pinnedSeeds(request, sources, blocked)
        val interests = generatedSeeds(request, sources, liked, blocked)
        val automatic = interests.filterNot { it.discovery }.roundRobinSources().take(if (request.additionalSection) 1 else 4)
        val discovery = if (request.additionalSection) null else interests.firstOrNull { it.discovery }
        (pinned + automatic + listOfNotNull(discovery)).distinctBy { sectionSeedId(it.tags) }
            .mapConcurrent { seed ->
                val feed = execute(request, seed.tags, liked.size)
                val lookup = CodexSaveRelevance.fromTags(liked.filter { it.postId.source in seed.tags }.flatMap { it.tags })
                val ranked = feed.page.items.sortedByDescending { lookup.score(listOf(it)) }
                ForYouSection(sectionSeedId(seed.tags), seed.title,
                    feed.copy(page = feed.page.copy(items = ranked)), seed.savedSearchId, seed.discovery)
            }
    }

    private suspend fun pinnedSeeds(request: ForYouFeedRequest, sources: List<SourceKey>,
        blocked: List<com.theoriacodex.data.repository.ForYouBlacklistEntry>): List<SectionSeed> {
        return savedSearches.observeSavedSearches().first().filter { entry ->
            !request.additionalSection && entry.search.kind == RecentSearchKind.FYP && entry.search.sourceTags.isNotEmpty() &&
                sources.containsAll(entry.search.sourceTags.keys) && allowedSection(entry.search.sourceTags, request, blocked)
        }.take(6).map { entry ->
            SectionSeed(entry.search.sourceTags, entry.name, savedSearchId = entry.id)
        }
    }

    private suspend fun generatedSeeds(request: ForYouFeedRequest, sources: List<SourceKey>,
        liked: List<com.theoriacodex.data.repository.LikedPost>,
        blocked: List<com.theoriacodex.data.repository.ForYouBlacklistEntry>): List<SectionSeed> {
        return sources.mapConcurrent { source ->
            val documents = liked.filter { it.postId.source == source }.map { it.tags }
            val tags = documents.flatMap { RecommendationTagNormalization.normalizeDistinct(source, it, 40) }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(48).map { it.key }
            val providerCounts = if (tags.isEmpty()) emptyMap() else counts.fetch(source, tags, emptyMap())
            ForYouInterests.generate(source, documents, providerCounts,
                random = if (request.shuffle) Random.Default else null,
                maxInterests = if (request.additionalSection) 4 + request.excludedSectionIds.size else 4).map { interest ->
                SectionSeed(mapOf(source to interest.tags), interest.tags.joinToString(" + "), discovery = interest.discovery)
            }
        }.flatten().filter { seed ->
            allowedSection(seed.tags, request, blocked)
        }
    }

    suspend fun pin(section: ForYouSection, pinned: Boolean): String? {
        if (!pinned) {
            section.savedSearchId?.let { savedSearches.remove(it) }
            return null
        }
        return savedSearches.save(section.title.take(80), RecentSearchEntry(
            query = requireNotNull(section.feed.continuation).query,
            queryHash = "for_you:${section.id}", searchedAtEpochMs = System.currentTimeMillis(),
            kind = RecentSearchKind.FYP, sources = section.feed.seed.keys.toList(), sourceTags = section.feed.seed,
        )).id
    }
}

private data class SectionSeed(
    val tags: Map<SourceKey, List<String>>,
    val title: String,
    val savedSearchId: String? = null,
    val discovery: Boolean = false,
)

private fun List<SectionSeed>.roundRobinSources(): List<SectionSeed> {
    val groups = groupBy { it.tags.keys.first() }.values.toList()
    return (0 until (groups.maxOfOrNull { it.size } ?: 0)).flatMap { index -> groups.mapNotNull { it.getOrNull(index) } }
}

private fun seedKey(tags: List<String>) = tags.map(String::lowercase).sorted().joinToString("+")

private fun sectionSeedId(tags: Map<SourceKey, List<String>>): String =
    buildForYouSeedId(tags.mapValues { (_, values) -> values.sorted() })

private fun allowedSection(tags: Map<SourceKey, List<String>>, request: ForYouFeedRequest,
    blocked: List<com.theoriacodex.data.repository.ForYouBlacklistEntry>): Boolean =
    sectionSeedId(tags) !in request.excludedSectionIds && tags.none { (source, values) ->
        blocked.any { it.source == source && seedKey(it.tags) == seedKey(values) }
    }
