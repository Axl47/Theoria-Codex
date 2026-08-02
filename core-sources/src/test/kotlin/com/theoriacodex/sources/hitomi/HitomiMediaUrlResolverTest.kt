package com.theoriacodex.sources.hitomi

import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitomiMediaUrlResolverTest {
    @Test
    fun `parses captured base version and shard routing`() {
        val configuration = HitomiMediaUrlResolver.parseConfiguration(fixture())

        assertEquals("1783681201/", configuration.basePath)
        assertEquals("1783681201", configuration.version)
        assertEquals(setOf(2644, 253, 1063), configuration.shardCaseKeys)
        assertEquals(2, configuration.caseShard)
    }

    @Test
    fun `parses current inverted shard routing without reversing its primary host`() = runTest {
        val configuration = HitomiMediaUrlResolver.parseConfiguration(currentFixture())
        val resolver = HitomiMediaUrlResolver(QueueHttpClient(currentFixture()))

        assertEquals("1783688401/", configuration.basePath)
        assertEquals(setOf(1133, 156, 1010), configuration.shardCaseKeys)
        assertEquals(1, configuration.caseShard)

        val caseHash = "0".repeat(61) + "6d4"
        val defaultHash = "0".repeat(61) + "54a"
        assertEquals(1133, caseHash.takeLast(3).let { value ->
            "${value.last()}${value.take(2)}".toInt(16)
        })
        val caseCandidates = resolver.candidates(
            HitomiMediaFile(hash = caseHash, name = "page.webp", hasAvif = false),
        )
        val defaultCandidates = HitomiMediaUrlResolver(QueueHttpClient(currentFixture())).candidates(
            HitomiMediaFile(hash = defaultHash, name = "page.webp", hasAvif = false),
        )

        assertEquals(listOf(1, 2, 1, 2), caseCandidates.map(HitomiMediaCandidate::shard))
        assertEquals(listOf(2, 1, 2, 1), defaultCandidates.map(HitomiMediaCandidate::shard))
    }

    @Test
    fun `derives avif webp and original candidates with alternate shards`() = runTest {
        val client = QueueHttpClient(fixture())
        val resolver = HitomiMediaUrlResolver(client)
        val hash = "2733fb24ac9ec065e6af94adb500554e56093df4481a92e85425955f368fa7f1"

        val candidates = resolver.candidates(
            HitomiMediaFile(hash = hash, name = "01.webp", hasAvif = true),
        )

        assertEquals(
            listOf(
                HitomiMediaFormat.AVIF,
                HitomiMediaFormat.AVIF,
                HitomiMediaFormat.WEBP,
                HitomiMediaFormat.WEBP,
                HitomiMediaFormat.ORIGINAL,
                HitomiMediaFormat.ORIGINAL,
            ),
            candidates.map(HitomiMediaCandidate::format),
        )
        assertEquals(listOf(1, 2, 1, 2, 1, 2), candidates.map(HitomiMediaCandidate::shard))
        assertEquals(listOf(false, true, false, true, false, true), candidates.map { it.isAlternateShard })
        assertEquals(
            "https://a1.gold-usergeneratedcontent.net/1783681201/383/$hash.avif",
            candidates[0].url,
        )
        assertEquals(
            "https://w1.gold-usergeneratedcontent.net/1783681201/383/$hash.webp",
            candidates[2].url,
        )
        assertEquals(
            "https://1.gold-usergeneratedcontent.net/images/1783681201/383/$hash.webp",
            candidates[4].url,
        )
        assertTrue(candidates.all { it.configurationVersion == "1783681201" })
        assertTrue(candidates.all { it.configurationBasePath == "1783681201/" })
    }

    @Test
    fun `uses configured shard two first and skips avif when unavailable`() = runTest {
        val resolver = HitomiMediaUrlResolver(QueueHttpClient(fixture()))
        val shardTwoHash = "0".repeat(61) + "54a"

        val candidates = resolver.candidates(
            HitomiMediaFile(hash = shardTwoHash, name = "page.jpg", hasAvif = false),
        )

        assertEquals(
            listOf(
                HitomiMediaFormat.WEBP,
                HitomiMediaFormat.WEBP,
                HitomiMediaFormat.ORIGINAL,
                HitomiMediaFormat.ORIGINAL,
            ),
            candidates.map(HitomiMediaCandidate::format),
        )
        assertEquals(listOf(2, 1, 2, 1), candidates.map(HitomiMediaCandidate::shard))
        assertTrue(candidates.first().url.startsWith("https://w2."))
        assertEquals("image/jpeg", candidates[2].mime)
    }

    @Test
    fun `caches configuration until invalidated`() = runTest {
        val client = QueueHttpClient(fixture(), fixture())
        val resolver = HitomiMediaUrlResolver(client)
        val file = mediaFile()

        resolver.candidates(file)
        resolver.candidates(file)
        assertEquals(1, client.getCount)

        resolver.invalidate()
        resolver.candidates(file)
        assertEquals(2, client.getCount)
    }

    @Test
    fun `refreshes a stale failed version and reuses the newer configuration`() = runTest {
        val updatedFixture = fixture().replace("1783681201/", "1783689999/")
        val client = QueueHttpClient(fixture(), updatedFixture)
        val resolver = HitomiMediaUrlResolver(client)
        val file = mediaFile()
        val stale = resolver.candidates(file)

        val refreshed = resolver.refreshCandidates(
            file = file,
            failedConfigurationVersion = stale.first().configurationVersion,
        )
        val reused = resolver.refreshCandidates(
            file = file,
            failedConfigurationVersion = stale.first().configurationVersion,
        )

        assertEquals(2, client.getCount)
        assertEquals("1783689999", refreshed.first().configurationVersion)
        assertEquals(refreshed, reused)
    }

    @Test
    fun `refreshes only once when provider returns the same failed version`() = runTest {
        val client = QueueHttpClient(fixture(), fixture())
        val resolver = HitomiMediaUrlResolver(client)
        val file = mediaFile()
        val failedVersion = resolver.candidates(file).first().configurationVersion

        resolver.refreshCandidates(file, failedVersion)
        resolver.refreshCandidates(file, failedVersion)

        assertEquals(2, client.getCount)
    }

    @Test
    fun `cancellation escapes refresh and leaves the version eligible to retry`() = runTest {
        val updatedFixture = fixture().replace("1783681201/", "1783689999/")
        val client = QueueHttpClient(
            fixture(),
            CancellationException("cancel gg refresh"),
            updatedFixture,
        )
        val resolver = HitomiMediaUrlResolver(client)
        val file = mediaFile()
        val failedVersion = resolver.candidates(file).first().configurationVersion

        val failure = runCatching {
            resolver.refreshCandidates(file, failedVersion)
        }.exceptionOrNull()
        val retried = resolver.refreshCandidates(file, failedVersion)

        assertTrue(failure is CancellationException)
        assertEquals(3, client.getCount)
        assertEquals("1783689999", retried.first().configurationVersion)
    }

    @Test
    fun `non cancellation refresh failure consumes the failed version retry`() = runTest {
        val client = QueueHttpClient(
            fixture(),
            SourceHttpResponse(statusCode = 503, body = "unavailable"),
        )
        val resolver = HitomiMediaUrlResolver(client)
        val file = mediaFile()
        val initial = resolver.candidates(file)
        val failedVersion = initial.first().configurationVersion

        val failure = runCatching {
            resolver.refreshCandidates(file, failedVersion)
        }.exceptionOrNull()
        val guardedRetry = resolver.refreshCandidates(file, failedVersion)

        assertTrue(failure is HitomiProtocolException)
        assertEquals(2, client.getCount)
        assertEquals(initial, guardedRetry)
    }

    @Test
    fun `failed configuration refresh guards evict by an observable byte budget`() = runTest {
        val versions = listOf("1783681201", "1783681202", "1783681203", "1783681204")
        val client = QueueHttpClient(
            *versions.map { version -> fixture().replace("1783681201", version) }.toTypedArray(),
        )
        val resolver = HitomiMediaUrlResolver(client, failureVersionCacheMaxBytes = 10L)
        val file = mediaFile()
        var failedVersion = resolver.candidates(file).first().configurationVersion

        repeat(3) {
            failedVersion = resolver.refreshCandidates(file, failedVersion)
                .first()
                .configurationVersion
        }

        val snapshot = resolver.failureVersionCacheSnapshot()
        assertEquals(10L, snapshot.maxBytes)
        assertEquals(10L, snapshot.cachedBytes)
        assertEquals(listOf("1783681203"), snapshot.keysInLruOrder)
        assertEquals(listOf("1783681203" to 10L), snapshot.weightsInLruOrder)
    }

    @Test
    fun `rejects malformed configuration and unsafe file inputs`() = runTest {
        val missingShardFunction = "gg = { b: '1783681201/' };"
        val unsafeBase = fixture().replace("1783681201/", "../media/")
        val sameShardBranches = currentFixture().replace("o = 0; break;", "o = 1; break;")
        assertProtocolFailure { HitomiMediaUrlResolver.parseConfiguration(missingShardFunction) }
        assertProtocolFailure { HitomiMediaUrlResolver.parseConfiguration(unsafeBase) }
        assertProtocolFailure { HitomiMediaUrlResolver.parseConfiguration(sameShardBranches) }

        val resolver = HitomiMediaUrlResolver(QueueHttpClient(fixture(), fixture()))
        val invalidHash = runCatching {
            resolver.candidates(HitomiMediaFile(hash = "abc", name = "01.webp", hasAvif = true))
        }.exceptionOrNull()
        val invalidName = runCatching {
            resolver.candidates(HitomiMediaFile(hash = "0".repeat(64), name = "no-extension", hasAvif = true))
        }.exceptionOrNull()

        assertTrue(invalidHash is HitomiProtocolException)
        assertTrue(invalidName is HitomiProtocolException)
    }

    @Test
    fun `reports configuration URL and status when gg request fails`() = runTest {
        val client = QueueHttpClient(SourceHttpResponse(statusCode = 503, body = "unavailable"))
        val failure = runCatching {
            HitomiMediaUrlResolver(client).candidates(mediaFile())
        }.exceptionOrNull()

        assertTrue(failure is HitomiProtocolException)
        assertTrue(failure?.message.orEmpty().contains(HitomiMediaUrlResolver.GG_CONFIGURATION_URL))
        assertTrue(failure?.message.orEmpty().contains("503"))
        assertEquals(HitomiMediaUrlResolver.GG_CONFIGURATION_URL, client.lastUrl)
        assertEquals(
            mapOf(
                "Accept" to "application/javascript, text/javascript, */*;q=0.8",
                "Referer" to "https://hitomi.la/",
                "User-Agent" to "Mozilla/5.0",
            ),
            client.lastHeaders,
        )
    }

    private fun mediaFile() = HitomiMediaFile(
        hash = "2733fb24ac9ec065e6af94adb500554e56093df4481a92e85425955f368fa7f1",
        name = "01.webp",
        hasAvif = true,
    )

    private fun fixture(): String = requireNotNull(
        javaClass.getResource("/hitomi/2026-07-10/gg-shape.js"),
    ) { "Missing Hitomi gg.js fixture" }.readText()

    private fun currentFixture(): String = requireNotNull(
        javaClass.getResource("/hitomi/2026-07-10/gg-current-inverted-shape.js"),
    ) { "Missing current Hitomi gg.js fixture" }.readText()

    private fun assertProtocolFailure(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected HitomiProtocolException, got $failure", failure is HitomiProtocolException)
    }

    private class QueueHttpClient(vararg outcomes: Any) : SourceHttpClient {
        private val outcomes = ArrayDeque(outcomes.toList())

        var getCount: Int = 0
            private set
        var lastUrl: String? = null
            private set
        var lastHeaders: Map<String, String> = emptyMap()
            private set

        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse {
            getCount += 1
            lastUrl = url
            lastHeaders = headers
            return when (val outcome = outcomes.removeFirst()) {
                is SourceHttpResponse -> outcome
                is String -> SourceHttpResponse(statusCode = 200, body = outcome)
                is Throwable -> throw outcome
                else -> error("Unsupported test outcome: $outcome")
            }
        }

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("Unexpected POST $url")
    }
}
