package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * Suggested save targets per post for one-tap save surfaces, ranked by [rankSaveTargets] like the picker.
 *
 * The active profile's Codices, their item lists and covers are observed once and shared by every post being
 * asked about; each post adds only its relevance lookup from [relevance]. Stops 5 s after the last collector.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveSuggestionSource(
    codices: CodexRepository,
    settings: SettingsRepository,
    collection: CodexCollectionSource,
    private val relevance: CodexSaveRelevanceLoader,
    private val searchSaves: SearchSaveMemory,
    scope: CoroutineScope,
) {
    private val library: SharedFlow<SuggestionLibrary> =
        combine(settings.observeSettings().map { it.activeProfileId }.distinctUntilChanged(), codices.observeCodices()) {
                profileId, all -> profileId to all.filter { codexBelongsToProfile(it.codexId, profileId) }
            }
            .distinctUntilChanged()
            .flatMapLatest { (profileId, profileCodices) -> observeLibrary(codices, collection, profileId, profileCodices) }
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 1)

    /** The top [limit] Codices [post] is not in yet, for a post shown from search [query]. */
    fun observe(post: Post, query: Query?, limit: Int): Flow<SaveSuggestions> =
        combine(library, relevance.observe(listOf(post), query)) { library, scores ->
            val containing = library.items.filterValues { items -> items.any { it.postId == post.id } }.keys
            val ranked = rankSaveTargets(
                library.codices, library.profileId, containing, scores, searchSaves, query, library.lastSavedAt,
            )
            SaveSuggestions(
                targets = ranked.suggestions.take(limit).map { codex ->
                    SaveSuggestion(codex.codexId, codex.name, library.covers[codex.codexId].orEmpty())
                },
                inAnyCodex = ranked.inAnyCodex,
            )
        }.distinctUntilChanged()

    private fun observeLibrary(
        codices: CodexRepository,
        collection: CodexCollectionSource,
        profileId: String,
        profileCodices: List<Codex>,
    ): Flow<SuggestionLibrary> {
        if (profileCodices.isEmpty()) return flowOf(SuggestionLibrary(profileId, emptyList(), emptyMap(), emptyMap()))
        val ids = profileCodices.mapTo(linkedSetOf(), Codex::codexId)
        val items = combine(ids.map { id ->
            codices.observeCodexItems(id).map { id to it }.catch { emit(id to emptyList()) }
        }) { pairs -> pairs.toMap() }
        val covers = collection.observe(ids).map { it.coverCandidates }.catch { emit(emptyMap()) }
        return combine(items, covers) { byCodex, coverCandidates ->
            SuggestionLibrary(profileId, profileCodices, byCodex, coverCandidates)
        }
    }

    private class SuggestionLibrary(
        val profileId: String,
        val codices: List<Codex>,
        val items: Map<String, List<CodexItem>>,
        val covers: Map<String, List<CodexCoverCandidate>>,
    ) {
        val lastSavedAt: Map<String, Long> = latestSaveTimes(items)
    }
}
