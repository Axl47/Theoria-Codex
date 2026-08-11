package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.mutateAndPersistWithRollback
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FileBackedUiRestoreRepository(
    baseDirectory: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UiRestoreRepository {
    private val mutex = Mutex()
    private val storageFile = baseDirectory.resolve("ui_restore_store.json")
    private val fileStore = AtomicJsonFileStore(ioDispatcher = ioDispatcher)
    private val viewerContextFlow = MutableStateFlow<ViewerLaunchContext?>(null)
    private val scrollStates = mutableMapOf<String, SearchScrollState>()
    private var settingsSectionExpansion: Map<String, Boolean> = emptyMap()
    private val feedFabRestoreStates = mutableMapOf<String, FeedFabRestoreState>()
    private var lastTab: String? = null

    init {
        val stored = runBlocking { fileStore.read(storageFile, UiRestoreStoreFile()) }
        lastTab = stored.lastTab
        scrollStates.putAll(
            stored.searchScrollStates.mapValues { (_, record) ->
                SearchScrollState(
                    firstVisibleItemIndex = record.firstVisibleItemIndex,
                    firstVisibleItemOffsetPx = record.firstVisibleItemOffsetPx,
                )
            }
        )
        settingsSectionExpansion = stored.settingsSectionExpansion
        feedFabRestoreStates.putAll(stored.feedFabRestoreStates)
        viewerContextFlow.value = stored.viewerLaunchContext?.toDomain()
    }

    override suspend fun setLastTab(route: String) {
        mutex.withLock {
            commitMutation { lastTab = route }
        }
    }

    override suspend fun getLastTab(): String? {
        return lastTab
    }

    override suspend fun migrateLegacyLastTab(legacyRoute: String?): String? {
        return mutex.withLock {
            lastTab?.let { current -> return@withLock current }
            val migrated = legacyRoute?.trim()?.takeIf { route -> route.isNotEmpty() }
                ?: return@withLock null
            commitMutation {
                lastTab = migrated
                migrated
            }
        }
    }

    override suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState) {
        mutex.withLock {
            commitMutation { scrollStates[queryHash] = state }
        }
    }

    override suspend fun getSearchScrollState(queryHash: String): SearchScrollState? {
        return scrollStates[queryHash]
    }

    override suspend fun setSettingsSectionExpansion(expansion: Map<String, Boolean>) {
        mutex.withLock {
            commitMutation { settingsSectionExpansion = expansion.toMap() }
        }
    }

    override suspend fun getSettingsSectionExpansion(): Map<String, Boolean> {
        return settingsSectionExpansion
    }

    override suspend fun setFeedFabRestoreState(contextKey: String, state: FeedFabRestoreState) {
        val normalizedKey = contextKey.trim()
        if (normalizedKey.isBlank()) return
        mutex.withLock {
            commitMutation { feedFabRestoreStates[normalizedKey] = state }
        }
    }

    override suspend fun getFeedFabRestoreStates(): Map<String, FeedFabRestoreState> {
        return mutex.withLock { feedFabRestoreStates.toMap() }
    }

    override fun observeViewerLaunchContext(): Flow<ViewerLaunchContext?> {
        return viewerContextFlow
    }

    override suspend fun setViewerLaunchContext(context: ViewerLaunchContext?) {
        mutex.withLock {
            commitMutation { viewerContextFlow.value = context }
        }
    }

    private suspend inline fun <T> commitMutation(mutate: () -> T): T {
        return mutateAndPersistWithRollback(
            snapshot = {
                UiRestoreMemoryState(
                    lastTab = lastTab,
                    scrollStates = scrollStates.toMap(),
                    settingsSectionExpansion = settingsSectionExpansion,
                    feedFabRestoreStates = feedFabRestoreStates.toMap(),
                    viewerLaunchContext = viewerContextFlow.value,
                )
            },
            restore = { state ->
                lastTab = state.lastTab
                scrollStates.clear()
                scrollStates.putAll(state.scrollStates)
                settingsSectionExpansion = state.settingsSectionExpansion
                feedFabRestoreStates.clear()
                feedFabRestoreStates.putAll(state.feedFabRestoreStates)
                viewerContextFlow.value = state.viewerLaunchContext
            },
            mutate = mutate,
            persist = { persist() },
        )
    }

    private suspend fun persist() {
        fileStore.write(
            storageFile,
            UiRestoreStoreFile(
                lastTab = lastTab,
                searchScrollStates = scrollStates.mapValues { (_, state) ->
                    SearchScrollStateRecord(
                        firstVisibleItemIndex = state.firstVisibleItemIndex,
                        firstVisibleItemOffsetPx = state.firstVisibleItemOffsetPx,
                    )
                },
                settingsSectionExpansion = settingsSectionExpansion,
                feedFabRestoreStates = feedFabRestoreStates.toMap(),
                viewerLaunchContext = viewerContextFlow.value?.let(ViewerLaunchContextRecord::fromDomain),
            ),
        )
    }
}
