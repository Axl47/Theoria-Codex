package com.theoriacodex.app.recommend

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.triage.TriageDecision
import com.theoriacodex.data.repository.ForYouVerdict
import com.theoriacodex.data.repository.ForYouVerdictKind
import com.theoriacodex.data.repository.ForYouVerdictRepository
import com.theoriacodex.data.repository.InMemoryForYouVerdictRepository
import com.theoriacodex.domain.model.PostId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForYouVerdictLogTest {
    private val verdicts = InMemoryForYouVerdictRepository()
    private val first = testPost(DECK_SOURCE, "first")
    private val second = testPost(DECK_SOURCE, "second")

    private fun TestScope.log(repository: ForYouVerdictRepository = verdicts, clock: () -> Long = AtomicLong()::incrementAndGet) =
        ForYouVerdictLog(repository, backgroundScope, backgroundScope, clock)

    private suspend fun skipped(profileId: String, id: PostId, at: Long) =
        verdicts.record(ForYouVerdict(profileId, id, ForYouVerdictKind.SKIPPED, at, null))

    @Test fun `records a swipe with its preview, and revert removes it`() = runTest {
        val log = log()
        val liked = TriageDecision.Liked(first)
        val undone = TriageDecision.Skipped(second)
        log.record(liked, PROFILE)
        log.record(undone, PROFILE)
        log.record(TriageDecision.Kept(second), PROFILE)
        log.revert(undone)

        val logged = verdicts.verdicts(PROFILE)
        assertEquals(listOf(first.id to ForYouVerdictKind.LIKED), logged.map { it.postId to it.kind })
        assertEquals(first.preview.url, logged.single().previewUrl)
    }

    @Test fun `reads the profile's skips from the last 30 days only`() = runTest {
        val now = 40L * DAY_MS
        skipped("other", first.id, now)
        skipped(PROFILE, first.id, now - 31 * DAY_MS)
        skipped(PROFILE, second.id, now - 29 * DAY_MS)
        val log = log(clock = { now })
        log.readSkips(PROFILE)

        assertFalse(first.id in log)
        assertTrue(second.id in log)
    }

    @Test fun `a read that outlasts the wait still applies when it lands`() = runTest {
        skipped(PROFILE, first.id, 0)
        val slow = CompletableDeferred<Unit>()
        val held = object : ForYouVerdictRepository by verdicts {
            override suspend fun skippedBetween(profileId: String, sinceEpochMs: Long, untilEpochMs: Long, limit: Int): Set<PostId> {
                slow.await()
                return verdicts.skippedBetween(profileId, sinceEpochMs, untilEpochMs, limit)
            }
        }
        val log = log(held)
        log.readSkips(PROFILE)
        assertFalse(first.id in log)

        slow.complete(Unit)
        runCurrent()
        assertTrue(first.id in log)
    }

    @Test fun `a skip undone while the skips are read is not kept out by the read`() = runTest {
        val queried = CompletableDeferred<Unit>()
        val landed = CompletableDeferred<Unit>()
        val held = object : ForYouVerdictRepository by verdicts {
            override suspend fun skippedBetween(profileId: String, sinceEpochMs: Long, untilEpochMs: Long, limit: Int): Set<PostId> {
                queried.await()
                return verdicts.skippedBetween(profileId, sinceEpochMs, untilEpochMs, limit).also { landed.await() }
            }
        }
        val log = log(held)
        log.readSkips(PROFILE)
        val undone = TriageDecision.Skipped(first)
        log.record(undone, PROFILE)
        queried.complete(Unit)
        runCurrent()
        log.revert(undone)
        landed.complete(Unit)
        runCurrent()

        assertFalse(first.id in log)
    }

    @Test fun `reset for a profile forgets its skips and does not read them back, even after a failed read`() = runTest {
        skipped(PROFILE, first.id, 0)
        var down = true
        val flaky = object : ForYouVerdictRepository by verdicts {
            override suspend fun skippedBetween(profileId: String, sinceEpochMs: Long, untilEpochMs: Long, limit: Int) =
                if (down) error("down") else verdicts.skippedBetween(profileId, sinceEpochMs, untilEpochMs, limit)
        }
        val log = log(flaky)
        log.readSkips(PROFILE)
        assertFalse(first.id in log)

        down = false
        log.reset(readFor = PROFILE)
        log.readSkips(PROFILE)
        assertFalse(first.id in log)

        log.reset(readFor = null)
        log.readSkips(PROFILE)
        assertTrue(first.id in log)
    }
}

private const val PROFILE = "profile"
private const val DAY_MS = 24L * 60 * 60 * 1000
