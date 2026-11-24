package com.theoriacodex.app.fixtures

import com.theoriacodex.app.recommend.ForYouInterests
import com.theoriacodex.app.recommend.TagAffinityInputs
import com.theoriacodex.app.recommend.affinity
import com.theoriacodex.app.recommend.tagAffinityInputs
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.StatisticsTagKey
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeavyWatchedTagsTest {
    private val source = SourceKey.GELBOORU
    private val random = Random(306)
    private val cumulative = run {
        val weights = DoubleArray(VOCABULARY) { 1.0 / (it + 1.0).pow(ZIPF_EXPONENT) }
        val total = weights.sum()
        var running = 0.0
        DoubleArray(VOCABULARY) { running += weights[it] / total; running }
    }
    private val kept = List(KEPT) { post(INTERESTS.map { it.keptRate }) }
    private val watched = run {
        val keptAt = List(KEPT) { random.nextInt(WATCHED) }.withIndex().associate { it.value to it.index }
        List(WATCHED) { index -> keptAt[index]?.let(kept::get) ?: post(INTERESTS.map { it.watchedRate }) } +
            kept.filterIndexed { index, _ -> index !in keptAt.values }
    }
    private val likes = kept.mapIndexed { index, tags -> LikedPost("profile", PostId(source, "kept-$index"), 0L, tags) }

    @Test
    fun `a capped table keeps the interests, lanes and order exact counts give, and never raises a kept tag's lift`() {
        val exact = inputs(
            LifetimeStatistics(
                watchedBySource = mapOf(source to watched.size.toLong()),
                watchedByTag = watched.flatten().groupingBy { StatisticsTagKey(source, it) }.eachCount().mapValues { it.value.toLong() },
            ),
        )
        val capped = inputs(recorded())

        val candidates = kept.flatten().distinct()
        val interests = INTERESTS.map { it.tag }
        assertTrue(exact.watchedByTag.getValue(source).size > 20 * capped.watchedByTag.getValue(source).size)
        assertEquals(provenInterests(exact), provenInterests(capped))
        assertEquals(interests.toSet(), provenInterests(capped).toSet())
        assertEquals(lanes(exact).filter { it in interests }, lanes(capped).filter { it in interests })
        assertEquals(lanes(exact).size, lanes(capped).size)
        assertEquals(emptyList<String>(), candidates.filter { capped.lift(source, it)!! > exact.lift(source, it)!! + 1e-9 })
    }

    private fun recorded(): LifetimeStatistics = runBlocking {
        val repository = InMemoryStatisticsRepository()
        watched.forEach { repository.recordWatchedPost(source, it.toSet()) }
        repository.observeStatistics().first()
    }

    private fun inputs(statistics: LifetimeStatistics) = tagAffinityInputs(likes, emptyList(), emptyList(), emptyList(), statistics)

    private fun provenInterests(inputs: TagAffinityInputs): List<String> =
        INTERESTS.map { it.tag }.filter { inputs.affinity(source, it).proven }.sortedByDescending { inputs.affinity(source, it).score }

    private fun lanes(inputs: TagAffinityInputs): List<String> = ForYouInterests.prepare(source, kept, inputs).pick().flatMap { it.tags }

    private fun post(interestRates: List<Double>): List<String> {
        val tags = LinkedHashSet<String>()
        INTERESTS.forEachIndexed { index, interest -> if (random.nextDouble() < interestRates[index]) tags += interest.tag }
        while (tags.size < TAGS_PER_POST) tags += "tag${rank(random.nextDouble())}"
        return tags.toList()
    }

    private fun rank(draw: Double): Int {
        var low = 0
        var high = VOCABULARY - 1
        while (low < high) {
            val middle = (low + high) / 2
            if (cumulative[middle] < draw) low = middle + 1 else high = middle
        }
        return low
    }

    private class Interest(val tag: String, val watchedRate: Double, val keptRate: Double)

    private companion object {
        const val VOCABULARY = 200_000
        const val ZIPF_EXPONENT = 1.05
        const val TAGS_PER_POST = 35
        const val WATCHED = 30_000
        const val KEPT = 800
        val INTERESTS = listOf(
            Interest("frieren", 0.06, 0.30), Interest("fern", 0.04, 0.22), Interest("hatsune_miku", 0.03, 0.18),
            Interest("kitagawa_marin", 0.02, 0.12), Interest("makima", 0.012, 0.08), Interest("power", 0.008, 0.06),
            Interest("yor_forger", 0.005, 0.04), Interest("anya_forger", 0.003, 0.03),
        )
    }
}
