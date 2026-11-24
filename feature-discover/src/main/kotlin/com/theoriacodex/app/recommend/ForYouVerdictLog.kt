package com.theoriacodex.app.recommend

import com.theoriacodex.app.media.postPreviewImageCandidate
import com.theoriacodex.app.triage.TriageDecision
import com.theoriacodex.data.repository.ForYouVerdict
import com.theoriacodex.data.repository.ForYouVerdictKind
import com.theoriacodex.data.repository.ForYouVerdictRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.PostId
import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class ForYouVerdictLog(
    private val repository: ForYouVerdictRepository,
    private val writeScope: CoroutineScope,
    private val readScope: CoroutineScope,
    private val clock: () -> Long,
) {
    private var skips: Set<PostId> = emptySet()
    private var skipsReadFor: String? = null
    private var read: Job? = null
    private val logged = IdentityHashMap<TriageDecision, ForYouVerdict>()
    private val writes = Mutex()

    operator fun contains(id: PostId): Boolean = id in skips

    fun hasSkipsFor(profileId: String): Boolean = skipsReadFor == profileId

    fun record(decision: TriageDecision, profileId: String) {
        val kind = decision.verdictKind() ?: return
        val post = decision.post
        val verdict = ForYouVerdict(profileId, post.id, kind, clock(), postPreviewImageCandidate(post)?.url)
        logged[decision] = verdict
        write { record(verdict) }
    }

    fun revert(decision: TriageDecision) {
        val verdict = logged.remove(decision) ?: return
        write { remove(verdict) }
    }

    suspend fun readSkips(profileId: String) {
        if (skipsReadFor == profileId) return
        val until = clock()
        val since = until - SKIP_MEMORY_MS
        val job = readScope.launch {
            writes.withLock {}
            skips = runCatchingPreservingCancellation { repository.skippedBetween(profileId, since, until, SKIP_READ_LIMIT) }
                .onFailure { Diagnostics.report("for-you", "For You could not read its recent skips", it) }
                .getOrNull() ?: return@launch
            skipsReadFor = profileId
        }
        read = job
        withTimeoutOrNull(SKIP_READ_TIMEOUT_MS) { job.join() }
    }

    fun reset(readFor: String?) {
        read?.cancel()
        read = null
        skips = emptySet()
        skipsReadFor = readFor
    }

    private fun write(write: suspend ForYouVerdictRepository.() -> Unit) {
        writeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            writes.withLock {
                runCatchingPreservingCancellation { repository.write() }
                    .onFailure { Diagnostics.report("for-you", "A For You verdict could not be logged", it) }
            }
        }
    }
}

internal fun TriageDecision.verdictKind(): ForYouVerdictKind? = when (this) {
    is TriageDecision.Liked -> ForYouVerdictKind.LIKED
    is TriageDecision.Saved -> ForYouVerdictKind.SAVED
    is TriageDecision.Skipped -> ForYouVerdictKind.SKIPPED
    is TriageDecision.Hid -> ForYouVerdictKind.HID
    is TriageDecision.Kept, is TriageDecision.Removed -> null
}

private const val SKIP_MEMORY_MS = 30L * 24 * 60 * 60 * 1000
private const val SKIP_READ_LIMIT = 5_000
private const val SKIP_READ_TIMEOUT_MS = 1_500L
