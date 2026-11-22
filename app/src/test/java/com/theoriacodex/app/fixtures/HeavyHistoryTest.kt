package com.theoriacodex.app.fixtures

import com.theoriacodex.app.recommend.affinity
import com.theoriacodex.app.recommend.tagAffinityInputs
import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.StatisticsTagKey
import com.theoriacodex.domain.model.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heavy-history journey expects one outcome from [HeavyHistory]; this pins the affinity behind it on the host, so
 * retuning a For You threshold fails here, before the Main branch's emulator run.
 */
class HeavyHistoryTest {
    private val history = HeavyHistory(ImageRef(null, null, null))
    private val source = HeavyHistory.SOURCE
    private val inputs = tagAffinityInputs(
        likes = history.likes.map { LikedPost("profile-main", it.id, 0L, it.canonicalTags) },
        codexPosts = history.codices.values.flatten(),
        savedTags = emptyList(),
        savedSearches = emptyList(),
        // As recordWatchedPost counts them: each watched post once per tag.
        statistics = LifetimeStatistics(
            watchedBySource = mapOf(source to history.watched.size.toLong()),
            watchedByTag = history.watched.flatMap(List<String>::distinct)
                .groupingBy { StatisticsTagKey(source, it) }.eachCount().mapValues { it.value.toLong() },
        ),
    )

    private fun affinity(tag: String) = inputs.affinity(source, tag)

    @Test
    fun onlyTheCharactersTheProfileSeeksOutAreProven() {
        val tags = history.catalog.flatMap { it.canonicalTags }.distinct()
        assertEquals(setOf("fern", "frieren", "hatsune_miku", "kitagawa_marin"), tags.filter { affinity(it).proven }.toSet())
        assertTrue(affinity("frieren").score > affinity("fern").score)
    }

    @Test
    fun everydayAndImageTextTagsAreNeverOffered() {
        assertTrue(affinity("blush").universal)
        assertFalse(affinity("artist_name").informative)
        assertFalse(affinity("smile").proven)
    }
}
