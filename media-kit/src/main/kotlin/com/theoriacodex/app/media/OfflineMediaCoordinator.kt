package com.theoriacodex.app.media

import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.repository.OfflinePendingJobStore
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

/**
 * Application-owned jobs survive collection navigation. Only completed galleries count as available.
 *
 * A job is registered and saved to the pending queue at once, then waits for [awaitSources] before it resolves
 * its first post, so [recoverPendingJobs] can run during startup, before sources are ready, without failing posts.
 */
class OfflineMediaCoordinator(
    val store: OfflineMediaStore,
    private val scope: CoroutineScope,
    private val resolve: suspend (Post) -> Post,
    private val selectMedia: suspend (Post) -> List<ImageRef>,
    private val acquire: suspend (Post, ImageRef, File) -> Unit,
    private val postTimeoutMs: Long = OFFLINE_POST_TIMEOUT_MS,
    private val pendingStore: OfflinePendingJobStore? = null,
    private val ownerExists: suspend (String) -> Boolean = { true },
    private val awaitSources: suspend () -> Unit = {},
) {
    private val slots = Semaphore(2)
    private val postLocks = List(16) { Mutex() }
    private val lock = Any()
    private val persistence = Mutex()
    private val active = mutableMapOf<String, Job>()
    private val preparations = mutableMapOf<String, Job>()
    // Owners being removed (with how many removals run), and how many clears run: no job is admitted for them then.
    private val removing = mutableMapOf<String, Int>()
    private var clearing = 0
    private val requests = mutableMapOf<String, List<Post>>()
    private val mutableJobs = MutableStateFlow<Map<String, OfflineMediaJob>>(emptyMap())
    val jobs: StateFlow<Map<String, OfflineMediaJob>> = mutableJobs

    init { scope.launch { store.load() } }

    suspend fun recoverPendingJobs() {
        val recovered = com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation { pendingStore?.load().orEmpty() }
        if (recovered.isFailure) {
            update(AUTOMATIC_OFFLINE_OWNER, OfflineMediaJob(0, isRunning = false,
                failureReason = "The offline queue could not be restored. Retry to try again."))
            return
        }
        val retained = recovered.getOrThrow().filterKeys { ownerExists(it) }
        val restoredJobs = retained.mapNotNull { (owner, ids) ->
            enqueueOffline(owner, ids.map { id ->
                Post(id, ImageRef(null, null, null), null, pageUrl = null, width = null, height = null,
                    canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null)
            }, start = false)
        }
        // Register every owner before any worker can checkpoint the recovered intent.
        restoredJobs.forEach(Job::start)
    }

    private suspend fun checkpoint() {
        val store = pendingStore ?: return
        persistence.withLock {
            val snapshot = synchronized(lock) {
                requests.filterKeys { owner -> mutableJobs.value[owner]?.let { !it.isCancelled && (it.isRunning || it.failedPosts.isNotEmpty()) } == true }
                    .mapValues { (_, posts) -> posts.map(Post::id) }
            }
            store.save(snapshot)
        }
    }

    fun makeAvailableOffline(owner: String, posts: List<Post>) { enqueueOffline(owner, posts, start = true) }

    /**
     * Runs [prepare] as [owner]'s preparation: it loads what to keep, then passes it to `start`, which starts the
     * job like [makeAvailableOffline]. `start` admits it only while this preparation is [owner]'s current one and not
     * cancelled, checked under the lock that admits jobs (which also refuses during a removal or clear). A newer preparation,
     * [cancel], [remove] and [clear] cancel it, so a list loaded before any of them can never start a job (for an
     * owner that is gone, or a request the user stopped). The returned job completes when it ends.
     */
    fun prepare(owner: String, prepare: suspend (start: (List<Post>) -> Unit) -> Unit): Job {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            prepare { posts ->
                synchronized(lock) {
                    if (preparations[owner] === job && job.isActive) enqueueOffline(owner, posts, start = true)
                }
            }
        }
        synchronized(lock) { preparations.put(owner, job) }?.cancel()
        job.invokeOnCompletion { synchronized(lock) { if (preparations[owner] === job) preparations.remove(owner) } }
        job.start()
        return job
    }

    /**
     * Downloads [posts] (and only them) into [owner]'s offline copy, so a Codex kept offline stays complete as
     * posts are saved into it. A running job takes them at its tail; an owner with copies starts a job for them
     * alone. A request the user cancelled stays stopped, adding [posts] to what Retry runs, and an owner with
     * neither copies nor a running job gets nothing.
     */
    fun keepOffline(owner: String, posts: List<Post>) {
        val job = jobs.value[owner]
        if (job?.isCancelled == true) {
            synchronized(lock) { requests[owner] = (requests[owner].orEmpty() + posts).distinctBy(Post::id) }
            return
        }
        if (job?.isRunning != true && owner !in store.snapshot.value.postsByOwner) return
        enqueueOffline(owner, posts, start = true)
    }

    /**
     * The one place every offline job is admitted. While [remove] runs for [owner] or [clear] runs it admits nothing,
     * so they stop every job admitted before them and no later one can write a copy after they clear.
     */
    private fun enqueueOffline(owner: String, posts: List<Post>, start: Boolean): Job? {
        require(owner.isNotBlank())
        synchronized(lock) {
            if (clearing > 0 || owner in removing) return null
            if (active[owner]?.let { !it.isCompleted && !it.isCancelled } == true) {
                appendToRunningJob(owner, posts)
                return null
            }
            val unique = posts.distinctBy(Post::id)
            requests[owner] = unique
            update(owner, OfflineMediaJob(totalPosts = unique.size))
            val previous = active[owner]
            val job = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                synchronized(lock) { update(owner, OfflineMediaJob(totalPosts = requests[owner].orEmpty().size)) }
                val result = com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation {
                    checkpoint()
                    awaitSources()
                    download(owner)
                }
                if (result.isFailure) update(owner, OfflineMediaJob(requests[owner].orEmpty().size,
                    failedPosts = requests[owner].orEmpty().mapTo(mutableSetOf(), Post::id), isRunning = false,
                    failureReason = "The offline queue could not be saved. Please retry."))
            }
            active[owner] = job
            if (start) job.start()
            return job
        }
    }

    /** A running job takes [posts] at its tail; repeating its own posts changes nothing. Called holding the lock. */
    private fun appendToRunningJob(owner: String, posts: List<Post>) {
        val merged = (requests[owner].orEmpty() + posts).distinctBy(Post::id)
        if (merged.size == requests[owner]?.size) return
        if (owner == AUTOMATIC_OFFLINE_OWNER) check(merged.size <= MAX_AUTOMATIC_OFFLINE_POSTS) { "The offline save queue is full" }
        requests[owner] = merged
        mutableJobs.update { jobs ->
            val current = jobs[owner] ?: return@update jobs
            jobs + (owner to current.copy(totalPosts = merged.size))
        }
        checkpointInBackground(owner)
    }

    fun retry(owner: String) {
        val posts = synchronized(lock) { requests[owner] }
        if (posts != null) makeAvailableOffline(owner, posts)
        else scope.launch { recoverPendingJobs() }
    }

    fun cancel(owner: String) {
        synchronized(lock) { preparations[owner]?.cancel(); active[owner]?.cancel() }
        mutableJobs.update { jobs ->
            val job = jobs[owner] ?: return@update jobs
            jobs + (owner to job.copy(isRunning = false, isCancelled = true))
        }
        checkpointInBackground(owner)
    }

    private fun checkpointInBackground(owner: String) {
        scope.launch {
            val result = com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation { checkpoint() }
            if (result.isFailure) mutableJobs.update { jobs ->
                jobs[owner]?.let { job -> jobs + (owner to job.copy(failureReason = "The offline queue update could not be saved.")) } ?: jobs
            }
        }
    }

    suspend fun remove(owner: String) {
        synchronized(lock) { removing[owner] = (removing[owner] ?: 0) + 1 }
        try {
            synchronized(lock) { preparations[owner] }?.cancelAndJoin()
            synchronized(lock) { active[owner] }?.cancelAndJoin()
            mutableJobs.update { jobs -> jobs[owner]?.let { jobs + (owner to it.copy(isRunning = false, isCancelled = true)) } ?: jobs }
            checkpoint()
            store.removeOwner(owner)
            mutableJobs.update { it - owner }
            synchronized(lock) { requests.remove(owner); active.remove(owner) }
        } finally {
            synchronized(lock) { removing.computeIfPresent(owner) { _, count -> (count - 1).takeIf { it > 0 } } }
        }
    }

    suspend fun clear() {
        synchronized(lock) { clearing++ }
        try {
            val running = synchronized(lock) { preparations.values + active.values }
            running.forEach { it.cancel() }
            running.forEach { it.join() }
            mutableJobs.update { jobs -> jobs.mapValues { (_, job) -> job.copy(isRunning = false, isCancelled = true) } }
            checkpoint()
            store.clear()
            mutableJobs.value = emptyMap()
            synchronized(lock) { requests.clear(); active.clear() }
        } finally {
            synchronized(lock) { clearing-- }
        }
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
                checkpoint()
                synchronized(lock) {
                    update(owner, OfflineMediaJob(requests[owner].orEmpty().size, completed, failed.toSet()))
                }
            }
        } finally {
            if (!drained) synchronized(lock) {
                update(owner, OfflineMediaJob(requests[owner].orEmpty().size, completed, failed, isRunning = false, isCancelled = true))
            }
            com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation { checkpoint() }
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
    val failureReason: String? = null,
) {
    val failureMessage: String? get() = failureReason ?: if (failedPosts.isEmpty()) null else
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
