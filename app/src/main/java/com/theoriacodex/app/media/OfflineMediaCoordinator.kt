package com.theoriacodex.app.media

import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Application-owned jobs survive collection navigation. Only completed galleries count as available. */
class OfflineMediaCoordinator(
    val store: OfflineMediaStore,
    private val scope: CoroutineScope,
    private val resolve: suspend (Post) -> Post,
    private val selectMedia: suspend (Post) -> List<ImageRef>,
    private val acquire: suspend (Post, ImageRef, File) -> Unit,
    private val postTimeoutMs: Long = OFFLINE_POST_TIMEOUT_MS,
) {
    private val slots = Semaphore(2)
    private val postLocks = List(16) { Mutex() }
    private val lock = Any()
    private val active = mutableMapOf<String, Job>()
    private val requests = mutableMapOf<String, List<Post>>()
    private val mutableJobs = MutableStateFlow<Map<String, OfflineMediaJob>>(emptyMap())
    val jobs: StateFlow<Map<String, OfflineMediaJob>> = mutableJobs

    init { scope.launch { store.refresh() } }

    fun makeAvailableOffline(owner: String, posts: List<Post>) {
        require(owner.isNotBlank())
        synchronized(lock) {
            if (active[owner]?.isActive == true) {
                if (owner == AUTOMATIC_OFFLINE_OWNER) {
                    val merged = (requests[owner].orEmpty() + posts).distinctBy(Post::id)
                    check(merged.size <= MAX_AUTOMATIC_OFFLINE_POSTS) { "The offline save queue is full" }
                    requests[owner] = merged
                    mutableJobs.update { jobs ->
                        val current = jobs[owner] ?: return@update jobs
                        jobs + (owner to current.copy(totalPosts = merged.size))
                    }
                }
                return
            }
            val unique = posts.distinctBy(Post::id)
            requests[owner] = unique
            update(owner, OfflineMediaJob(totalPosts = unique.size))
            val previous = active[owner]
            val job = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                synchronized(lock) { update(owner, OfflineMediaJob(totalPosts = requests[owner].orEmpty().size)) }
                download(owner)
            }
            active[owner] = job
            job.start()
        }
    }

    fun retry(owner: String) {
        val posts = synchronized(lock) { requests[owner] } ?: return
        makeAvailableOffline(owner, posts)
    }

    fun cancel(owner: String) {
        synchronized(lock) { active[owner]?.cancel() }
        mutableJobs.update { jobs ->
            val job = jobs[owner] ?: return@update jobs
            jobs + (owner to job.copy(isRunning = false, isCancelled = true))
        }
    }

    suspend fun remove(owner: String) {
        synchronized(lock) { active[owner] }?.cancelAndJoin()
        store.removeOwner(owner)
        mutableJobs.update { it - owner }
        synchronized(lock) { requests.remove(owner); active.remove(owner) }
    }

    suspend fun clear() {
        val running = synchronized(lock) { active.values.toList() }
        running.forEach { it.cancel() }
        running.forEach { it.join() }
        store.clear()
        mutableJobs.value = emptyMap()
        synchronized(lock) { requests.clear(); active.clear() }
    }

    suspend fun find(id: PostId): Post? = store.find(id)

    fun withoutOfflineLocations(post: Post): Post = store.withoutOfflineLocations(post)

    /** Admit app-owned work immediately after collection commit; saving feedback never waits on the network. */
    fun saveOnCollectionSave(post: Post) { makeAvailableOffline(AUTOMATIC_OFFLINE_OWNER, listOf(post)) }

    private suspend fun savePost(post: Post, owner: String): Post {
        val admission = store.admit(owner)
        return postLocks[(post.id.hashCode() and Int.MAX_VALUE) % postLocks.size].withLock {
            store.retain(post.id, owner, admission) ?: slots.withPermit {
                withTimeoutOrNull(postTimeoutMs) {
                    val resolved = resolve(post)
                    check(resolved.id == post.id) { "The provider returned a different post" }
                    val media = selectMedia(resolved)
                    store.save(resolved, media, owner, admission) { ref, output -> acquire(resolved, ref, output) }
                } ?: throw java.io.IOException("Offline download timed out")
            }
        }
    }

    private suspend fun download(owner: String) {
        var completed = 0
        var settled = 0
        var drained = false
        val failed = linkedSetOf<PostId>()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val post = synchronized(lock) {
                    val pending = requests[owner].orEmpty()
                    pending.getOrNull(settled).also { next ->
                        if (next == null) {
                            drained = true
                            update(owner, OfflineMediaJob(pending.size, completed, failed, isRunning = false))
                            active.remove(owner)
                        }
                    }
                } ?: return
                try {
                    savePost(post, owner)
                    completed++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed += post.id
                }
                settled++
                synchronized(lock) {
                    update(owner, OfflineMediaJob(requests[owner].orEmpty().size, completed, failed.toSet()))
                }
            }
        } finally {
            if (!drained) synchronized(lock) {
                update(owner, OfflineMediaJob(requests[owner].orEmpty().size, completed, failed, isRunning = false, isCancelled = true))
            }
        }
    }

    private fun update(owner: String, job: OfflineMediaJob) { mutableJobs.update { it + (owner to job) } }
}

data class OfflineMediaJob(
    val totalPosts: Int,
    val completedPosts: Int = 0,
    val failedPosts: Set<PostId> = emptySet(),
    val isRunning: Boolean = true,
    val isCancelled: Boolean = false,
) {
    val failureMessage: String? get() = if (failedPosts.isEmpty()) null else
        "Some posts could not be saved. Check your connection and download preferences, then retry."
}

/** Full-media caching must acquire bytes; remote URL pointer files are never treated as downloads. */
class OfflineAwareCacheRepository(
    private val delegate: CacheRepository,
    private val offline: OfflineMediaCoordinator,
) : CacheRepository by delegate {
    override suspend fun cacheFull(post: Post) = offline.saveOnCollectionSave(post)
}

const val AUTOMATIC_OFFLINE_OWNER = "saved-posts"
private const val MAX_AUTOMATIC_OFFLINE_POSTS = 1_000
private const val OFFLINE_POST_TIMEOUT_MS = 10 * 60_000L
