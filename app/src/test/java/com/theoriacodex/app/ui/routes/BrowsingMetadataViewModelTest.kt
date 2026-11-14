package com.theoriacodex.app.ui.routes

import com.theoriacodex.app.codex.LikesCodexSyncService
import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.app.post.HASH
import com.theoriacodex.app.post.OTHER
import com.theoriacodex.app.post.filePost
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.testing.InMemoryCodexLikesTransactions
import com.theoriacodex.data.repository.*
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowsingMetadataViewModelTest {
    @Test fun `multiple feed consumers share ID queries and Recents decodes one payload stream`() = runTest {
        val settings = InMemorySettingsRepository()
        val likes = InMemoryLikesRepository()
        val recentStore = InMemoryRecentsRepository()
        var likedSubscriptions = 0
        var fullLikeSubscriptions = 0
        var watchedPayloadSubscriptions = 0
        var searchSubscriptions = 0
        val countedLikes = object : LikesRepository by likes {
            override fun observeLikedPostIds(profileId: String) = flow {
                likedSubscriptions++
                emitAll(likes.observeLikedPostIds(profileId))
            }
            override fun observeLikes(profileId: String) = flow {
                fullLikeSubscriptions++
                emitAll(likes.observeLikes(profileId))
            }
        }
        val countedRecents = object : RecentsRepository by recentStore {
            override fun observeWatchedPosts() = flow {
                watchedPayloadSubscriptions++
                emitAll(recentStore.observeWatchedPosts())
            }
            override fun observeSearches() = flow {
                searchSubscriptions++
                emitAll(recentStore.observeSearches())
            }
        }
        val owner = BrowsingMetadataViewModel(settings, countedLikes, InMemoryCodexRepository(), countedRecents,
            backgroundScope, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { owner.state.collect() }
        backgroundScope.launch { owner.state.collect() }
        runCurrent()
        assertEquals(1, likedSubscriptions)
        assertEquals(0, fullLikeSubscriptions)
        // One read of the watch history teaches the file mirror index; nothing stays subscribed for badges.
        assertEquals(1, watchedPayloadSubscriptions)

        backgroundScope.launch { owner.recents.collect() }
        backgroundScope.launch { owner.recents.collect() }
        runCurrent()
        val profile = requireNotNull(owner.state.value).settings.activeProfileId
        val post = Post(PostId(SourceKey.PIXIV, "1"), ImageRef(null, null, null), null,
            pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
            authorName = null, createdAtEpochMs = null)
        recentStore.recordWatchedPost(post, ViewerStreamSource.SEARCH, null)
        likes.toggleLike(profile, post.id, listOf("cat"))
        settings.setCacheFullImageOnSave(true)
        runCurrent()
        assertEquals(1, likedSubscriptions)
        assertEquals(2, watchedPayloadSubscriptions)
        assertEquals(1, searchSubscriptions)
        assertEquals(0, fullLikeSubscriptions)
        assertEquals(setOf(post.id), owner.state.value?.watchedPostIds)
        assertEquals(1, owner.recents.value?.activity?.size)

        val next = settings.addRecommendationProfile("New")
        runCurrent()
        assertEquals(next.profileId, owner.state.value?.settings?.activeProfileId)
        assertTrue(owner.state.value?.likedPostIds?.isEmpty() == true)
        assertFalse(owner.state.value?.watchedPostIds?.isEmpty() == true)
        assertEquals(2, likedSubscriptions)
        assertEquals(2, watchedPayloadSubscriptions)
    }

    @Test fun `a copy of a liked file on another site counts as liked and is hidden by Hide liked`() = runTest {
        val settings = InMemorySettingsRepository()
        val transactions = InMemoryCodexLikesTransactions()
        val mirrors = FileMirrorIndex()
        val owner = BrowsingMetadataViewModel(settings, transactions.likes, transactions.codices, InMemoryRecentsRepository(),
            backgroundScope, StandardTestDispatcher(testScheduler), mirrors)
        val emissions = mutableListOf<BrowsingMetadata>()
        backgroundScope.launch { owner.state.collect { it?.let(emissions::add) } }
        runCurrent()
        val profile = settings.observeSettings().first().activeRecommendationProfile()
        val liked = filePost(SourceKey.DANBOORU, "1", HASH)
        LikesCodexSyncService(transactions, transactions.codices, transactions.likes, mirrors).toggle(profile, liked, emptyList())
        runCurrent()
        val mirror = filePost(SourceKey.GELBOORU, "9", HASH)
        val unrelated = filePost(SourceKey.GELBOORU, "8", OTHER)

        mirrors.learn(listOf(unrelated))
        runCurrent()
        val beforeMirror = emissions.size
        mirrors.learn(listOf(mirror, unrelated))
        runCurrent()

        val metadata = requireNotNull(owner.state.value)
        assertEquals(setOf(liked.id, mirror.id), metadata.likedPostIds)
        assertEquals(beforeMirror + 1, emissions.size)
        assertEquals(listOf(unrelated), filterSearchResults(listOf(mirror, unrelated), SearchVisibilityFilters(hideLiked = true),
            metadata.likedPostIds, metadata.savedPostIds))
        mirrors.learn(listOf(filePost(SourceKey.PIXIV, "3", "a".repeat(32))))
        runCurrent()
        assertEquals(beforeMirror + 1, emissions.size)
    }

    @Test fun `only Codices people make count toward codexPostIds, not Likes`() = runTest {
        val settings = InMemorySettingsRepository()
        val transactions = InMemoryCodexLikesTransactions()
        val owner = BrowsingMetadataViewModel(settings, transactions.likes, transactions.codices, InMemoryRecentsRepository(),
            backgroundScope, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { owner.state.collect() }
        runCurrent()
        val profile = settings.observeSettings().first().activeRecommendationProfile()
        val liked = filePost(SourceKey.DANBOORU, "1", HASH)
        val kept = filePost(SourceKey.DANBOORU, "2", OTHER)
        LikesCodexSyncService(transactions, transactions.codices, transactions.likes, FileMirrorIndex()).toggle(profile, liked, emptyList())
        transactions.codices.addItems(transactions.codices.createCodex("Kept").codexId, listOf(kept))
        runCurrent()

        val metadata = requireNotNull(owner.state.value)
        assertEquals(setOf(liked.id, kept.id), metadata.savedPostIds)
        assertEquals(setOf(kept.id), metadata.codexPostIds)
    }

    @Test fun `a Library stored before browsing starts is read once and its copies count as liked`() = runTest {
        val settings = InMemorySettingsRepository()
        val transactions = InMemoryCodexLikesTransactions()
        val profile = settings.observeSettings().first().activeRecommendationProfile()
        val sync = LikesCodexSyncService(transactions, transactions.codices, transactions.likes)
        // More posts than are read one by one, so this takes the single Library read a real start takes.
        (1..25).forEach { n -> sync.toggle(profile, filePost(SourceKey.DANBOORU, "$n", "%032x".format(n)), emptyList()) }
        val mirrors = FileMirrorIndex()
        val owner = BrowsingMetadataViewModel(settings, transactions.likes, transactions.codices, InMemoryRecentsRepository(),
            backgroundScope, StandardTestDispatcher(testScheduler), mirrors)
        backgroundScope.launch { owner.state.collect() }
        runCurrent()

        val mirror = filePost(SourceKey.GELBOORU, "9", "%032x".format(7))
        mirrors.learn(listOf(mirror))
        runCurrent()

        assertTrue(mirror.id in requireNotNull(owner.state.value).likedPostIds)
    }
}
