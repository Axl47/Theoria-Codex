package com.theoriacodex.app.fixtures

import com.theoriacodex.data.repository.WATCHED_TAGS_PER_SOURCE
import com.theoriacodex.data.repository.CodexPostStatistics
import com.theoriacodex.data.repository.CodexStatistics
import com.theoriacodex.data.repository.DailyActivity
import com.theoriacodex.data.repository.DataStoreStatisticsRepository
import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.data.repository.STATISTICS_HISTORY_DAYS
import com.theoriacodex.data.repository.SearchedTagKey
import com.theoriacodex.data.repository.StatisticsTagKey
import com.theoriacodex.data.storage.withDataStoreScope
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey
import java.io.File
import java.time.LocalDate
import java.util.Random
import kotlin.math.roundToLong

object AgedStatistics {
    val viewsBySource: Map<SourceKey, Long> = linkedMapOf(
        SourceKey.PIXIV to 60_000L,
        SourceKey.GELBOORU to 140_000L,
        SourceKey.DANBOORU to 45_000L,
        SourceKey.RULE34XXX to 90_000L,
    )

    suspend fun write(storageDirectory: File, today: LocalDate = LocalDate.now()) = withDataStoreScope { scope ->
        DataStoreStatisticsRepository(storageDirectory, scope).mergeRestored(statistics(today))
    }

    fun statistics(today: LocalDate): LifetimeStatistics {
        val random = Random(SEED)
        val zipf = Zipf()
        val watched = viewsBySource.map { (source, views) -> watchedTags(source, views, random, zipf) }
        val history = history(today, random)
        val codices = codices(random)
        val searches = viewsBySource.keys.associateWith { source -> searchedTags(source, random, zipf) }
        val days = history.values
        return LifetimeStatistics(
            history = history,
            codexStatistics = codices,
            appOpenCount = days.sumOf(DailyActivity::appOpenCount),
            totalForegroundMs = days.sumOf(DailyActivity::totalForegroundMs),
            browsingMs = days.sumOf(DailyActivity::browsingMs),
            watchingMs = days.sumOf(DailyActivity::watchingMs),
            codexMs = days.sumOf(DailyActivity::codexMs),
            readingMs = days.sumOf(DailyActivity::readingMs),
            animeMs = days.sumOf(DailyActivity::animeMs),
            watchedPostCount = viewsBySource.values.sum(),
            watchedBySource = viewsBySource,
            watchedByTag = watched.fold(emptyMap()) { all, table -> all + table.counts },
            watchedTagFloors = watched.associate { table -> table.source to table.floor },
            searchCount = days.sumOf(DailyActivity::searchCount),
            searchesBySource = searches.mapValues { (_, counts) -> counts.values.sum() },
            searchesByTag = searches.values.fold(emptyMap()) { all, counts -> all + counts },
            searchesByTagSeeded = true,
            searchesByTypedTagSeeded = true,
            forYouSearchCount = 1_000L + random.nextInt(4_000),
            forYouSaveCount = 200L + random.nextInt(800),
            postUrlCopyCount = 50L + random.nextInt(300),
            codexEntryCounts = codices.keys.associateWith { 1L + random.nextInt(400) },
        )
    }

    fun postTags(posts: Int): List<List<String>> {
        val zipf = Zipf()
        return List(posts) { post ->
            val random = Random(SEED + post)
            val ranks = linkedSetOf<Int>()
            while (ranks.size < TAGS_PER_POST) ranks += zipf.rank(random)
            ranks.map(::tagName)
        }
    }

    private class WatchedTable(val source: SourceKey, val counts: Map<StatisticsTagKey, Long>, val floor: Long)

    private fun watchedTags(source: SourceKey, views: Long, random: Random, zipf: Zipf): WatchedTable {
        val floor = zipf.expectedCount(FLOOR_RANK, views).roundToLong().coerceAtLeast(1L)
        val used = HashSet<Int>()
        val counts = (1..WATCHED_TAGS_PER_SOURCE).associate { rank ->
            val churned = rank > CHURN_FROM_RANK && random.nextDouble() < CHURN_SHARE
            val tagRank = if (churned) unusedRareRank(random, used) else rank
            used += tagRank
            val count = if (churned) {
                floor + 1L + random.nextInt(CHURN_COUNT_SPREAD)
            } else {
                val expected = zipf.expectedCount(rank, views)
                maxOf(floor + 1L, (expected + random.nextGaussian() * StrictMath.sqrt(expected)).roundToLong())
            }
            StatisticsTagKey(source, sourceTagKey(source, tagName(tagRank))) to count
        }
        return WatchedTable(source, counts, floor)
    }

    private fun unusedRareRank(random: Random, used: Set<Int>): Int {
        while (true) {
            val rank = WATCHED_TAGS_PER_SOURCE + 1 + random.nextInt(VOCABULARY - WATCHED_TAGS_PER_SOURCE)
            if (rank !in used) return rank
        }
    }

    private fun history(today: LocalDate, random: Random): Map<LocalDate, DailyActivity> {
        val dailyViews = viewsBySource.values.sum().toDouble() / LIFETIME_DAYS
        return (0 until STATISTICS_HISTORY_DAYS).associate { offset ->
            val browsing = minutes(5 + random.nextInt(40))
            val watching = minutes(15 + random.nextInt(90))
            val codex = minutes(random.nextInt(20))
            val reading = minutes(random.nextInt(15))
            val anime = if (random.nextInt(5) == 0) minutes(random.nextInt(60)) else 0L
            today.minusDays(offset) to DailyActivity(
                appOpenCount = 1L + random.nextInt(10),
                totalForegroundMs = browsing + watching + codex + reading + anime,
                browsingMs = browsing,
                watchingMs = watching,
                codexMs = codex,
                readingMs = reading,
                animeMs = anime,
                watchedPostCount = (dailyViews * (0.5 + random.nextDouble())).roundToLong(),
                searchCount = 2L + random.nextInt(30),
            )
        }
    }

    private fun codices(random: Random): Map<String, CodexStatistics> = (0 until CODICES).associate { codex ->
        val posts = (0 until POSTS_PER_CODEX).associate { post ->
            val source = viewsBySource.keys.elementAt(post % viewsBySource.size)
            "${source.id}:${100_000 + codex * POSTS_PER_CODEX + post}" to CodexPostStatistics(
                label = "Saved ${tagName(1 + random.nextInt(WATCHED_TAGS_PER_SOURCE))} ${post + 1}",
                opens = 1L + random.nextInt(25),
                videoMs = if (post % 3 == 0) random.nextInt(600_000).toLong() else 0L,
                imageMs = random.nextInt(120_000).toLong(),
            )
        }
        "aged-codex-$codex" to CodexStatistics(
            browsingMs = minutes(10 + random.nextInt(600)),
            watchingMs = posts.values.sumOf { it.videoMs + it.imageMs },
            posts = posts,
        )
    }

    private fun searchedTags(source: SourceKey, random: Random, zipf: Zipf): Map<SearchedTagKey, Long> {
        val ranks = linkedSetOf<Int>()
        while (ranks.size < SEARCHED_TAGS_PER_SOURCE) ranks += zipf.rank(random)
        return ranks.withIndex().associate { (order, rank) ->
            SearchedTagKey(source, SearchTerm(tagName(rank))) to (SEARCH_SCALE / (order + 1)).coerceAtLeast(1L) + random.nextInt(3)
        }
    }

    private fun minutes(count: Int): Long = count * 60_000L

    private fun zipfWeight(rank: Int): Double = 1.0 / StrictMath.pow(rank.toDouble(), ZIPF_EXPONENT)

    private class Zipf {
        private val cumulative = run {
            var running = 0.0
            DoubleArray(VOCABULARY) { index -> running += zipfWeight(index + 1); running }
        }
        private val harmonic = cumulative.last()

        fun expectedCount(rank: Int, views: Long): Double =
            views * (1.0 - StrictMath.pow(1.0 - zipfWeight(rank) / harmonic, TAGS_PER_POST.toDouble()))

        fun rank(random: Random): Int {
            val target = random.nextDouble() * harmonic
            var low = 0
            var high = VOCABULARY - 1
            while (low < high) {
                val middle = (low + high) ushr 1
                if (cumulative[middle] < target) low = middle + 1 else high = middle
            }
            return low + 1
        }
    }

    private fun tagName(rank: Int): String {
        val word = StringBuilder()
        var rest = rank
        while (rest > 0) {
            word.append(SYLLABLES[rest % SYLLABLES.size])
            rest /= SYLLABLES.size
        }
        return "${word}_${NOUNS[rank % NOUNS.size]}"
    }

    private const val SEED = 476L
    private const val VOCABULARY = 200_000
    private const val ZIPF_EXPONENT = 1.05
    private const val TAGS_PER_POST = 35
    private const val CHURN_FROM_RANK = 2_000
    private const val CHURN_SHARE = 0.15
    private const val CHURN_COUNT_SPREAD = 20
    private const val FLOOR_RANK = 5_000
    private const val LIFETIME_DAYS = 730
    private const val CODICES = 24
    private const val POSTS_PER_CODEX = 150
    private const val SEARCHED_TAGS_PER_SOURCE = 600
    private const val SEARCH_SCALE = 400L
    private val SYLLABLES = listOf(
        "ka", "ri", "mo", "na", "sa", "to", "yu", "ra", "me", "ko", "su", "hi",
        "no", "ta", "mi", "ze", "ro", "ha", "ku", "se", "an", "el", "or", "vi",
    )
    private val NOUNS = listOf(
        "hair", "eyes", "dress", "sky", "smile", "ribbon", "gloves", "boots",
        "skirt", "hat", "sword", "wings", "tail", "flower", "night", "water",
    )
}
