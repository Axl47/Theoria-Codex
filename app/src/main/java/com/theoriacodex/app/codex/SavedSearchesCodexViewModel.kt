package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.model.SourceKey

internal const val SAVED_SEARCHES_CODEX_ID = "system:saved-searches"
internal fun isVirtualCodex(id: String) = id == FOLLOWED_CODEX_ID || id == SAVED_SEARCHES_CODEX_ID

/** A query collection owns live continuations, never durable Codex memberships. */
internal class SavedSearchesCodexViewModel(
    private val coordinator: SearchCoordinator,
    private val loader: SavedSearchFeedLoader,
    cache: CacheRepository,
) : ViewModel(), LiveCodexFeedOwner {
    private val feed = LiveCodexFeedController<SavedSearchBranch>(viewModelScope, loader::load,
        cacheCover = { post -> cache.cacheNamedThumbnail(SAVED_SEARCHES_CODEX_ID, post) },
    )
    override val state = feed.state
    private var entries: List<SavedSearchEntry> = emptyList()
    private var available: Set<SourceKey>? = null
    private var runtime: com.theoriacodex.data.repository.SourceRuntimeSettings? = null

    fun synchronize(selected: List<SavedSearchEntry>, settings: AppSettings, sources: Set<SourceKey>,
        sort: CodexSortMode = CodexSortMode.NEWEST_SAVED) {
        feed.updateCoverSort(sort)
        loader.updateAvailableSources(sources)
        coordinator.updateEnvironment(settings.copy(runtime = settings.runtime.copy(
            enabledSources = settings.runtime.enabledSources.intersect(sources),
        )))
        val changed = selected.map { it.id to it.search } != entries.map { it.id to it.search } ||
            sources != available || settings.runtime != runtime
        entries = selected
        available = sources
        runtime = settings.runtime
        if (changed) refresh()
    }

    override fun refresh() = feed.reset(entries.map(::SavedSearchBranch))
    override fun loadMore() = feed.loadMore()
    override fun retry() = feed.retry()

    companion object {
        fun factory(coordinator: SearchCoordinator, cache: CacheRepository) = viewModelFactory {
            initializer { SavedSearchesCodexViewModel(coordinator, SavedSearchFeedLoader(coordinator), cache) }
        }
    }
}
