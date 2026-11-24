package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.InMemorySavedSearchRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.data.repository.repositoryTestPost
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTagAffinityTest {
    @Test
    fun `a profile's inputs wait out a switch that has not reached them (ADR 0085)`() = runTest {
        val byProfile = MutableSharedFlow<Pair<String, TagAffinityInputs>>(replay = 1)
        val affinity = SharedTagAffinity(byProfile, backgroundScope)
        byProfile.emit("before" to TagAffinityInputs(likes = 1))

        val asked = async { affinity.forProfile("after") }
        runCurrent()
        assertFalse(asked.isCompleted)
        byProfile.emit("after" to TagAffinityInputs(likes = 2))
        assertEquals(2, asked.await().likes)
    }

    @Test
    fun `inputs follow the active profile and read only its own Codices (ADR 0085)`() = runTest {
        val settings = InMemorySettingsRepository()
        val main = settings.observeSettings().first().activeProfileId
        val other = settings.addRecommendationProfile("Other").profileId
        settings.setActiveProfile(main)
        val codex = InMemoryCodexRepository()
        codex.ensureCodex(ProfileLibraryIds.codex(main, "a"), "Main")
        codex.addItems(ProfileLibraryIds.codex(main, "a"), listOf(tagged("m", "moth")))
        codex.ensureCodex(ProfileLibraryIds.codex(other, "b"), "Other")
        codex.addItems(ProfileLibraryIds.codex(other, "b"), listOf(tagged("l", "lamp")))
        val affinity = share(settings, codex)

        val mainInterest = affinity.forProfile(main).interest.keys
        settings.setActiveProfile(other)
        val otherInterest = affinity.forProfile(other).interest.keys

        assertTrue(mainInterest.toString(), "moth" in mainInterest && "lamp" !in mainInterest)
        assertTrue(otherInterest.toString(), "lamp" in otherInterest && "moth" !in otherInterest)
    }

    @Test
    fun `a failing library read gives empty inputs at once and retries after one, then two seconds (ADR 0085)`() = runTest {
        val settings = InMemorySettingsRepository()
        val main = settings.observeSettings().first().activeProfileId
        val inner = InMemoryCodexRepository()
        inner.ensureCodex(ProfileLibraryIds.codex(main, "a"), "Main")
        inner.addItems(ProfileLibraryIds.codex(main, "a"), listOf(tagged("m", "moth")))
        val attempts = mutableListOf<Long>()
        val failingTwice = object : CodexRepository by inner {
            override fun observeCodices() = flow {
                attempts += testScheduler.currentTime
                if (attempts.size <= 2) throw IOException("library unavailable")
                emitAll(inner.observeCodices())
            }
        }
        val affinity = share(settings, failingTwice)

        val first = async { affinity.forProfile(main) }
        runCurrent()
        assertTrue(first.isCompleted)
        assertEquals(TagAffinityInputs.Empty, first.await())
        advanceTimeBy(3_001)

        assertEquals(listOf(0L, 1_000L, 3_000L), attempts)
        assertTrue("moth" in affinity.forProfile(main).interest)
    }

    private fun tagged(id: String, tag: String) =
        repositoryTestPost(id).copy(rawTags = listOf(tag), canonicalTags = listOf(tag), taxonomy = emptyList())

    private fun TestScope.share(settings: InMemorySettingsRepository, codex: CodexRepository) = shareTagAffinityInputs(
        backgroundScope, settings, InMemoryLikesRepository(), codex, InMemorySavedSearchRepository(), InMemoryStatisticsRepository(),
        computationDispatcher = StandardTestDispatcher(testScheduler),
    )
}
