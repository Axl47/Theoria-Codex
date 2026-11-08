package com.theoriacodex.data.repository

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared ranking for both the screen and the durable thumbnail owner. */
fun rankedStatisticsPosts(
    posts: Map<String, CodexPostStatistics>,
    value: (CodexPostStatistics) -> Long,
): List<Map.Entry<String, CodexPostStatistics>> = posts.entries.filter { value(it.value) > 0L }
    .sortedWith(compareByDescending<Map.Entry<String, CodexPostStatistics>> { value(it.value) }.thenBy { it.key })
    .take(5)

enum class StatisticsPostList { OPENED, VIDEOS, IMAGES }

data class StatisticsPostPromotion(val codexId: String, val list: StatisticsPostList, val postKey: String)

fun statisticsPostPromotions(statistics: LifetimeStatistics): Set<StatisticsPostPromotion> =
    statistics.codexStatistics.flatMapTo(linkedSetOf()) { (codexId, codex) ->
        listOf(
            StatisticsPostList.OPENED to rankedStatisticsPosts(codex.posts) { it.opens },
            StatisticsPostList.VIDEOS to rankedStatisticsPosts(codex.posts) { it.videoMs },
            StatisticsPostList.IMAGES to rankedStatisticsPosts(codex.posts) { it.imageMs },
        ).flatMap { (list, posts) -> posts.map { StatisticsPostPromotion(codexId, list, it.key) } }
    }

fun promotedStatisticsPostKeys(statistics: LifetimeStatistics): Set<String> =
    statisticsPostPromotions(statistics).mapTo(linkedSetOf()) { it.postKey }

/** Private files owned solely by the union of all Codex top lists, outside disposable caches. */
class StatisticsThumbnailStore(private val directory: File) {
    private val mutex = Mutex()

    /** Fetch only explicit promotion/open candidates. A partial or failed fetch never escapes this owner. */
    suspend fun reconcile(
        promoted: Set<String>,
        candidates: Map<String, suspend (File) -> Boolean> = emptyMap(),
    ): Map<String, String> = mutex.withLock {
        directory.mkdirs()
        val retainedNames = promoted.mapTo(hashSetOf()) { fileName(it) }
        directory.listFiles().orEmpty().filter { it.name !in retainedNames }.forEach { it.delete() }
        for (key in promoted) {
            candidates[key]?.let { acquire(key, it) }
        }
        promoted.mapNotNull { key -> File(directory, fileName(key))
            .takeIf { it.isFile && it.length() > 0L }?.let { key to it.absolutePath } }.toMap()
    }

    private suspend fun acquire(key: String, fetch: suspend (File) -> Boolean) {
        val destination = File(directory, fileName(key))
        if (destination.isFile && destination.length() > 0L) return
        val temporary = File(directory, "${destination.name}.pending")
        try {
            if (fetch(temporary) && temporary.length() > 0L) {
                check(temporary.renameTo(destination)) { "Unable to promote statistics thumbnail" }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The next promotion or post-open can retry without affecting statistics recording.
        } finally {
            temporary.delete()
        }
    }

    private fun fileName(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8)).toHexString() + ".webp"
}
