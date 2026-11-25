package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.InMemorySavedSearchRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.data.repository.repositoryTestPost
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
        settings.addFavoriteTag(main, SourceKey.GELBOORU, listOf("fox"))
        settings.addFavoriteTag(other, SourceKey.GELBOORU, listOf("owl"))
        val likes = likesOf(
            main to listOf(tagged("lm", "newt")),
            other to listOf(tagged("lo", "crow")),
        )
        val affinity = share(settings, codex, likes)

        val mainInterest = affinity.forProfile(main).interest.keys
        settings.setActiveProfile(other)
        val otherInterest = affinity.forProfile(other).interest.keys

        assertTrue(mainInterest.toString(), "moth" in mainInterest && "fox" in mainInterest && "newt" in mainInterest && "lamp" !in mainInterest && "owl" !in mainInterest && "crow" !in mainInterest)
        assertTrue(otherInterest.toString(), "lamp" in otherInterest && "owl" in otherInterest && "crow" in otherInterest && "moth" !in otherInterest && "fox" !in otherInterest && "newt" !in otherInterest)
    }

    @Test
    fun `a read still running for the previous profile never reaches the latest inputs after a switch (ADR 0085)`() = runTest {
        val settings = InMemorySettingsRepository()
        val main = settings.observeSettings().first().activeProfileId
        val other = settings.addRecommendationProfile("Other").profileId
        settings.setActiveProfile(main)
        val inner = InMemoryCodexRepository()
        inner.ensureCodex(ProfileLibraryIds.codex(main, "a"), "Main")
        inner.addItems(ProfileLibraryIds.codex(main, "a"), listOf(tagged("m", "moth")))
        inner.ensureCodex(ProfileLibraryIds.codex(other, "b"), "Other")
        inner.addItems(ProfileLibraryIds.codex(other, "b"), listOf(tagged("l", "lamp")))
        val slowForMain = object : CodexRepository by inner {
            override fun observeLibraryPosts(codexIds: Set<String>) = flow {
                if (codexIds.any { ProfileLibraryIds.belongsTo(it, main) }) delay(5_000)
                emitAll(inner.observeLibraryPosts(codexIds))
            }
        }
        val affinity = share(settings, slowForMain)
        backgroundScope.launch { affinity.latest.collect {} }
        runCurrent()

        settings.setActiveProfile(other)
        runCurrent()
        assertTrue("lamp" in withTimeout(1_000) { affinity.forProfile(other) }.interest)
        advanceTimeBy(6_000)

        val latest = affinity.latest.value.interest.keys
        assertTrue(latest.toString(), "lamp" in latest && "moth" !in latest)
    }

    @Test
    fun `a failing library read gives empty inputs at once and retries after a growing pause (ADR 0085)`() = runTest {
        val failures = 2
        val settings = InMemorySettingsRepository()
        val main = settings.observeSettings().first().activeProfileId
        val inner = InMemoryCodexRepository()
        inner.ensureCodex(ProfileLibraryIds.codex(main, "a"), "Main")
        inner.addItems(ProfileLibraryIds.codex(main, "a"), listOf(tagged("m", "moth")))
        val attempts = mutableListOf<Long>()
        val failingTwice = object : CodexRepository by inner {
            override fun observeCodices() = flow {
                attempts += testScheduler.currentTime
                if (attempts.size <= failures) throw IOException("library unavailable")
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

    @Test
    fun `retries keep doubling up to their cap (ADR 0085)`() = runTest {
        val failures = 8
        val settings = InMemorySettingsRepository()
        val main = settings.observeSettings().first().activeProfileId
        val inner = InMemoryCodexRepository()
        inner.ensureCodex(ProfileLibraryIds.codex(main, "a"), "Main")
        inner.addItems(ProfileLibraryIds.codex(main, "a"), listOf(tagged("m", "moth")))
        val attempts = mutableListOf<Long>()
        val failing = object : CodexRepository by inner {
            override fun observeCodices() = flow {
                attempts += testScheduler.currentTime
                if (attempts.size <= failures) throw IOException("library unavailable")
                emitAll(inner.observeCodices())
            }
        }
        val affinity = share(settings, failing)

        val first = async { affinity.forProfile(main) }
        runCurrent()
        advanceTimeBy(191_001)

        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 63_000L, 127_000L, 191_000L), attempts)
        assertTrue("moth" in affinity.forProfile(main).interest)
        first.cancel()
    }

    private fun tagged(id: String, tag: String) =
        repositoryTestPost(id).copy(rawTags = listOf(tag), canonicalTags = listOf(tag), taxonomy = emptyList())

    private fun likesOf(vararg byProfile: Pair<String, List<Post>>) = object : LikesRepository {
        private val liked = byProfile.associate { (profile, posts) -> profile to posts.map { LikedPost(profile, it.id, 0L, it.rawTags) } }
        override fun observeLikes(profileId: String) = flowOf(liked[profileId].orEmpty())
        override fun observeLikedPostIds(profileId: String) = flowOf(liked[profileId].orEmpty().mapTo(mutableSetOf(), LikedPost::postId))
    }

    private fun TestScope.share(
        settings: InMemorySettingsRepository,
        codex: CodexRepository,
        likes: LikesRepository = InMemoryLikesRepository(),
    ) = shareTagAffinityInputs(
        backgroundScope, settings, likes, codex, InMemorySavedSearchRepository(), InMemoryStatisticsRepository(),
        computationDispatcher = StandardTestDispatcher(testScheduler),
    )
}
