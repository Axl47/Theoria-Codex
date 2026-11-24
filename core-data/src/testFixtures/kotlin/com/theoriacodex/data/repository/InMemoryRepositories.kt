package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.MemoryStateCell
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DEFAULT_RECENT_WATCHED_LIMIT = 200
private const val DEFAULT_RECENT_SEARCH_LIMIT = 100

class InMemoryCodexRepository : CodexRepository {
    private val mutex = Mutex()
    private val codices = MutableStateFlow<List<Codex>>(emptyList())
    private val itemsByCodex = MutableStateFlow<Map<String, List<CodexItem>>>(emptyMap())
    private val postsById = MutableStateFlow<Map<PostId, Post>>(emptyMap())

    override fun observeCodices(): Flow<List<Codex>> {
        return codices
    }

    override fun observeCodex(codexId: String): Flow<Codex?> {
        return codices.map { all -> all.firstOrNull { it.codexId == codexId } }
    }

    override suspend fun ensureCodex(codexId: String, name: String): Codex {
        return mutex.withLock {
            val current = codices.value
            val existing = current.firstOrNull { it.codexId == codexId }
            val resolvedName = CodexLikesPolicy.resolveUniqueCodexName(
                requestedName = name,
                existingCodices = current,
                excludeCodexId = codexId,
            )
            if (existing != null) {
                if (existing.name == resolvedName) {
                    existing
                } else {
                    val updated = existing.copy(name = resolvedName)
                    codices.value = codices.value.map { codex ->
                        if (codex.codexId == codexId) updated else codex
                    }
                    updated
                }
            } else {
                val created = Codex(
                    codexId = codexId,
                    name = resolvedName,
                    createdAtEpochMs = System.currentTimeMillis(),
                )
                codices.value = codices.value + created
                created
            }
        }
    }

    override suspend fun createCodex(name: String): Codex {
        return mutex.withLock {
            val resolvedName = CodexLikesPolicy.resolveUniqueCodexName(
                requestedName = name,
                existingCodices = codices.value,
            )
            val created = Codex(
                codexId = UUID.randomUUID().toString(),
                name = resolvedName,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            codices.value = codices.value + created
            created
        }
    }

    suspend fun reorderCodex(codexId: String, targetIndex: Int) {
        mutex.withLock {
            codices.value = FixtureRepositoryPolicies.reorderCodices(
                codices = codices.value,
                codexId = codexId,
                targetIndex = targetIndex,
            )
        }
    }

    override suspend fun renameCodex(codexId: String, name: String) {
        mutex.withLock {
            val current = codices.value
            val existing = current.firstOrNull { it.codexId == codexId } ?: return@withLock
            val resolvedName = CodexLikesPolicy.resolveUniqueCodexName(
                requestedName = name,
                existingCodices = current,
                excludeCodexId = codexId,
            )
            if (existing.name == resolvedName) return@withLock
            codices.value = codices.value.map {
                if (it.codexId == codexId) it.copy(name = resolvedName) else it
            }
        }
    }

    override suspend fun setAutomaticTag(codexId: String, tag: CodexAutomaticTag, enabled: Boolean) {
        mutex.withLock {
            codices.value = codices.value.map { codex ->
                if (codex.codexId == codexId) {
                    codex.copy(
                        automaticTags = CodexLikesPolicy.setAutomaticTag(
                            current = codex.automaticTags,
                            requested = tag,
                            enabled = enabled,
                        ),
                    )
                } else {
                    codex
                }
            }
        }
    }

    suspend fun deleteCodex(codexId: String) {
        mutex.withLock { removeCodex(codexId) }
    }

    private fun removeCodex(codexId: String) {
        val removed = itemsByCodex.value[codexId].orEmpty().map(CodexItem::postId)
        codices.value = codices.value.filterNot { it.codexId == codexId }
        itemsByCodex.value = itemsByCodex.value - codexId
        releaseOrphanedPosts(removed)
    }

    private fun releaseOrphanedPosts(candidates: Collection<PostId>) {
        val held = itemsByCodex.value.values.flatMapTo(mutableSetOf()) { items -> items.map(CodexItem::postId) }
        postsById.value = postsById.value - (candidates.toSet() - held)
    }

    override suspend fun deleteCodexForUndo(codexId: String): DeletedCodexSnapshot? = mutex.withLock {
        val position = codices.value.indexOfFirst { it.codexId == codexId }
        if (position < 0) return@withLock null
        val items = itemsByCodex.value[codexId].orEmpty()
        val snapshot = DeletedCodexSnapshot(codices.value[position], position, items, items.mapNotNull { postsById.value[it.postId] })
        removeCodex(codexId)
        snapshot
    }

    override suspend fun deleteCodexIfEmpty(codexId: String): Boolean = mutex.withLock {
        if (codices.value.none { it.codexId == codexId } || itemsByCodex.value[codexId].orEmpty().isNotEmpty()) return@withLock false
        removeCodex(codexId)
        true
    }

    override suspend fun restoreDeletedCodex(snapshot: DeletedCodexSnapshot) {
        mutex.withLock {
            val current = codices.value
            if (current.none { it.codexId == snapshot.codex.codexId }) {
                val name = CodexLikesPolicy.resolveUniqueCodexName(snapshot.codex.name, current)
                codices.value = current.toMutableList().apply {
                    add(snapshot.position.coerceAtMost(size), snapshot.codex.copy(name = name))
                }
            }
        }
        restoreItems(snapshot.items, snapshot.posts)
    }

    override fun observeCodexItems(codexId: String): Flow<List<CodexItem>> {
        return itemsByCodex.map { map -> map[codexId].orEmpty() }
    }

    override fun observeCodexPosts(codexId: String, sort: CodexSortMode): Flow<List<Post>> {
        return combine(itemsByCodex, postsById) { items, posts ->
            val pairs = items[codexId].orEmpty().mapNotNull { item ->
                posts[item.postId]?.let { post -> item to post }
            }
            FixtureRepositoryPolicies.sortCodexPairs(pairs, sort).map { it.second }
        }
    }

    override suspend fun getPost(postId: PostId): Post? {
        return postsById.value[postId]
    }

    override suspend fun addItems(codexId: String, posts: List<Post>): Int {
        return mutex.withLock {
            check(codices.value.any { it.codexId == codexId }) { "The selected Codex no longer exists" }
            val existing = itemsByCodex.value[codexId].orEmpty()
            val existingIds = existing.mapTo(mutableSetOf(), CodexItem::postId)
            val uniquePosts = posts.distinctBy(Post::id)
            val additions = uniquePosts.filter { it.id !in existingIds }.map { post ->
                CodexItem(codexId, post.id, System.currentTimeMillis())
            }
            val updatedPosts = postsById.value.toMutableMap()
            uniquePosts.forEach { post ->
                updatedPosts[post.id] = updatedPosts[post.id]?.let { mergeSparsePost(it, post) } ?: post
            }
            postsById.value = updatedPosts
            itemsByCodex.value = itemsByCodex.value + (codexId to (existing + additions))
            additions.size
        }
    }

    override fun observeCodexSummaries(codexIds: Set<String>, coverLimit: Int): Flow<List<CodexSummary>> =
        combine(codices, itemsByCodex, postsById) { allCodices, items, posts ->
            allCodices.filter { it.codexId in codexIds }.map { codex ->
                val members = items[codex.codexId].orEmpty()
                val covers = members.sortedWith(
                    compareByDescending<CodexItem> { it.savedAtEpochMs }
                        .thenBy { it.postId.source.id }.thenBy { it.postId.sourcePostId },
                ).take(coverLimit.coerceAtLeast(0)).mapNotNull { posts[it.postId] }
                CodexSummary(codex.codexId, members.size, covers)
            }
        }

    override fun observeSavedPostIds(codexIds: Set<String>): Flow<Set<PostId>> =
        itemsByCodex.map { items ->
            items.filterKeys { it in codexIds }.values.flatten().mapTo(linkedSetOf(), CodexItem::postId)
        }

    override suspend fun updatePost(post: Post) {
        mutex.withLock {
            if (post.id !in postsById.value) return@withLock
            postsById.value = postsById.value + (post.id to mergeSparsePost(postsById.value.getValue(post.id), post))
        }
    }

    override suspend fun removeItems(codexId: String, postIds: Set<PostId>) {
        if (postIds.isEmpty()) return
        mutex.withLock {
            val existing = itemsByCodex.value[codexId] ?: return@withLock
            itemsByCodex.value = itemsByCodex.value + (
                codexId to existing.filterNot { item -> item.postId in postIds }
            )
            releaseOrphanedPosts(postIds)
        }
    }

    override suspend fun restoreItems(items: List<CodexItem>, posts: List<Post>) {
        if (items.isEmpty()) return
        mutex.withLock {
            val validCodexIds = codices.value.mapTo(mutableSetOf(), Codex::codexId)
            val postsBySnapshotId = posts.associateBy(Post::id)
            val restoredPosts = mutableMapOf<PostId, Post>()
            val restoredItems = itemsByCodex.value.toMutableMap()
            items.groupBy(CodexItem::codexId).forEach { (codexId, snapshots) ->
                if (codexId !in validCodexIds) return@forEach
                val existing = restoredItems[codexId].orEmpty()
                val existingIds = existing.mapTo(mutableSetOf(), CodexItem::postId)
                val additions = snapshots.filter { item ->
                    item.postId !in existingIds && postsBySnapshotId[item.postId] != null
                }
                additions.forEach { item -> restoredPosts[item.postId] = postsBySnapshotId.getValue(item.postId) }
                restoredItems[codexId] = existing + additions
            }
            postsById.value = restoredPosts.entries.fold(postsById.value) { current, (postId, post) ->
                if (postId in current) current else current + (postId to post)
            }
            itemsByCodex.value = restoredItems
        }
    }
}

class InMemoryQueryRepository : QueryRepository by CellQueryRepository(MemoryStateCell(emptyMap()))

class InMemoryRecentsRepository(
    private val watchedLimit: Int = DEFAULT_RECENT_WATCHED_LIMIT,
    private val searchLimit: Int = DEFAULT_RECENT_SEARCH_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecentsRepository {
    private val mutex = Mutex()
    private val watched = MutableStateFlow<List<RecentPostEntry>>(emptyList())
    private val searches = MutableStateFlow<List<RecentSearchEntry>>(emptyList())

    override fun observeWatchedPosts(): Flow<List<RecentPostEntry>> {
        return watched
    }

    override fun observeWatchedPostIds(): Flow<Set<PostId>> = watched.map { posts ->
        posts.filter { it.section == RecentPostSection.WATCHED }.mapTo(linkedSetOf()) { it.post.id }
    }

    override fun observeSearches(): Flow<List<RecentSearchEntry>> {
        return searches
    }

    override suspend fun recordWatchedPost(
        post: Post,
        origin: ViewerStreamSource,
        originQueryHash: String?,
        section: RecentPostSection,
        viewedMediaNumber: Int,
    ) {
        mutex.withLock {
            val recordedAt = clock()
            val previousEntry = watched.value.firstOrNull { entry ->
                entry.post.id == post.id && entry.section == section
            }
            val effectiveOrigin = previousEntry?.origin
                .takeIf { origin == ViewerStreamSource.RECENTS } ?: origin
            val effectiveQueryHash = previousEntry?.originQueryHash
                .takeIf { origin == ViewerStreamSource.RECENTS } ?: originQueryHash
            val replacement = RecentPostEntry(
                post = post,
                viewedAtEpochMs = recordedAt,
                origin = effectiveOrigin,
                originQueryHash = effectiveQueryHash,
                section = section,
                maxViewedMediaNumber = maxOf(
                    previousEntry?.maxViewedMediaNumber ?: 1,
                    viewedMediaNumber.coerceAtLeast(1),
                ),
            )
            watched.value = FixtureRepositoryPolicies.normalizeRecentWatched(
                entries = listOf(replacement) + watched.value.filterNot { entry ->
                    entry.post.id == post.id && entry.section == section
                },
                limit = watchedLimit,
            )
        }
    }

    override suspend fun recordWatchedMediaProgress(
        post: Post,
        origin: ViewerStreamSource,
        originQueryHash: String?,
        section: RecentPostSection,
        viewedMediaNumber: Int,
    ) {
        mutex.withLock {
            val normalizedMediaNumber = viewedMediaNumber.coerceAtLeast(1)
            val existing = watched.value.firstOrNull { entry ->
                entry.post.id == post.id && entry.section == section
            }
            watched.value = if (existing == null) {
                val inserted = RecentPostEntry(
                    post = post,
                    viewedAtEpochMs = clock(),
                    origin = origin,
                    originQueryHash = originQueryHash,
                    section = section,
                    maxViewedMediaNumber = normalizedMediaNumber,
                )
                FixtureRepositoryPolicies.normalizeRecentWatched(
                    entries = listOf(inserted) + watched.value,
                    limit = watchedLimit,
                )
            } else {
                watched.value.map { entry ->
                    if (entry.post.id == post.id && entry.section == section) {
                        entry.copy(
                            maxViewedMediaNumber = maxOf(
                                entry.maxViewedMediaNumber,
                                normalizedMediaNumber,
                            )
                        )
                    } else {
                        entry
                    }
                }
            }
        }
    }

    override suspend fun recordSearch(
        query: Query,
        queryHash: String,
        kind: RecentSearchKind,
        sources: List<SourceKey>,
    ) {
        val normalizedHash = queryHash.trim()
        if (normalizedHash.isBlank()) return
        mutex.withLock {
            searches.value = FixtureRepositoryPolicies.recordSearch(
                entries = searches.value,
                entry = RecentSearchEntry(
                    query = query,
                    queryHash = normalizedHash,
                    searchedAtEpochMs = clock(),
                    kind = kind,
                    sources = sources.distinct(),
                ),
                limit = searchLimit,
            )
        }
    }

    override suspend fun restoreEntries(
        watchedPosts: List<RecentPostEntry>,
        searches: List<RecentSearchEntry>,
    ) {
        mutex.withLock {
            watched.value = FixtureRepositoryPolicies.normalizeRecentWatched(
                entries = watched.value + watchedPosts,
                limit = watchedLimit,
            )
            this.searches.value = FixtureRepositoryPolicies.normalizeRecentSearches(
                entries = this.searches.value + searches,
                limit = searchLimit,
            )
        }
    }

    override suspend fun removeWatchedPost(postId: PostId, section: RecentPostSection) {
        mutex.withLock {
            watched.value = watched.value.filterNot { entry -> entry.post.id == postId && entry.section == section }
        }
    }

    override suspend fun removeSearch(queryHash: String) {
        mutex.withLock {
            searches.value = searches.value.filterNot { entry -> entry.queryHash == queryHash.trim() }
        }
    }

    override suspend fun clearWatchedPosts(section: RecentPostSection) {
        mutex.withLock {
            watched.value = watched.value.filterNot { entry -> entry.section == section }
        }
    }

    override suspend fun clearSearches(): List<RecentSearchEntry> = mutex.withLock {
        searches.value.also { searches.value = emptyList() }
    }

    override suspend fun clearAll() {
        mutex.withLock {
            watched.value = emptyList()
            searches.value = emptyList()
        }
    }
}

class InMemorySettingsRepository : SettingsRepository by CellSettingsRepository(
    MemoryStateCell(RepositoryPolicies.normalizeSettings(AppSettings())),
)

class InMemoryUiRestoreRepository : UiRestoreRepository {
    private val mutex = Mutex()
    private val lastTab = MutableStateFlow<String?>(null)
    private val scrollStates = MutableStateFlow<Map<String, SearchScrollState>>(emptyMap())
    private val feedFabRestoreStates = MutableStateFlow<Map<String, FeedFabRestoreState>>(emptyMap())
    private val viewerLaunchContext = MutableStateFlow<ViewerLaunchContext?>(null)
    private val librarySection = MutableStateFlow<String?>(null)

    override suspend fun setLibrarySection(section: String) {
        librarySection.value = section
    }

    override suspend fun getLibrarySection(): String? = librarySection.value

    private val savedSearchNewPosts = MutableStateFlow(SavedSearchNewPosts())

    override fun observeSavedSearchNewPosts(): Flow<SavedSearchNewPosts> = savedSearchNewPosts

    override suspend fun updateSavedSearchNewPosts(transform: (SavedSearchNewPosts) -> SavedSearchNewPosts) {
        mutex.withLock { savedSearchNewPosts.value = transform(savedSearchNewPosts.value) }
    }

    override suspend fun setLastTab(route: String) {
        mutex.withLock {
            lastTab.value = route
        }
    }

    override suspend fun getLastTab(): String? {
        return lastTab.value
    }

    override suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState) {
        mutex.withLock {
            scrollStates.value = scrollStates.value + (queryHash to state)
        }
    }

    override suspend fun getSearchScrollState(queryHash: String): SearchScrollState? {
        return scrollStates.value[queryHash]
    }

    override suspend fun setFeedFabRestoreState(contextKey: String, state: FeedFabRestoreState) {
        val normalizedKey = contextKey.trim()
        if (normalizedKey.isBlank()) return
        mutex.withLock {
            feedFabRestoreStates.value = feedFabRestoreStates.value + (normalizedKey to state)
        }
    }

    override suspend fun getFeedFabRestoreStates(): Map<String, FeedFabRestoreState> {
        return feedFabRestoreStates.value
    }

    override fun observeViewerLaunchContext(): Flow<ViewerLaunchContext?> {
        return viewerLaunchContext
    }

    override suspend fun setViewerLaunchContext(context: ViewerLaunchContext?) {
        viewerLaunchContext.value = context
    }
}

suspend fun RecentsRepository.activity(): List<RecentActivityEntry> =
    mergeRecentActivity(observeWatchedPosts().first(), observeSearches().first())
