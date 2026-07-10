package com.theoriacodex.app.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.hitomi.HitomiMediaCandidate
import com.theoriacodex.sources.hitomi.HitomiMediaFormat
import com.theoriacodex.sources.hitomi.HitomiSourceAdapter
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live provider coverage is intentionally excluded from the default connected-device lane.
 * Run this class explicitly and pass the `theoriaLiveHitomi=true` instrumentation argument.
 */
@RunWith(AndroidJUnit4::class)
class HitomiLiveTransportDeviceTest {
    @Before
    fun requireExplicitLiveTransportOptIn() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(LIVE_HITOMI_ARGUMENT)
            ?.toBooleanStrictOrNull()
            ?: false
        assumeTrue(
            "Live Hitomi transport requires the $LIVE_HITOMI_ARGUMENT=true instrumentation argument",
            enabled,
        )
    }

    @Test
    fun typedAndMultiTagSearchesUseAndroidByteRangeTransport() = runBlocking {
        val adapter = HitomiSourceAdapter(
            httpClient = DefaultSourceHttpClient(),
            pageSize = 2,
            mediaCandidates = { file ->
                listOf(
                    HitomiMediaCandidate(
                        url = "https://example.invalid/${file.hash}.webp",
                        mime = "image/webp",
                        format = HitomiMediaFormat.WEBP,
                        shard = 1,
                        isAlternateShard = false,
                        configurationBasePath = "device-test",
                        configurationVersion = "device-test",
                    ),
                )
            },
        )

        withTimeout(60_000) {
            val globalGirl = adapter.search(query(SearchTerm("girl")), null)
            assertEquals(2, globalGirl.items.size)

            val gameCg = adapter.search(
                query(SearchTerm("gamecg", SearchFacet.TYPE, "type")),
                null,
            )
            assertEquals(2, gameCg.items.size)

            val multiTag = adapter.search(
                query(
                    SearchTerm("animated", SearchFacet.TAG, "tag"),
                    SearchTerm("x-ray", SearchFacet.TAG, "female"),
                ),
                null,
            )
            assertTrue(multiTag.items.isNotEmpty())
        }
    }

    private fun query(vararg terms: SearchTerm): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.HITOMI),
            includeTerms = terms.toList(),
            excludeTerms = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    private companion object {
        const val LIVE_HITOMI_ARGUMENT = "theoriaLiveHitomi"
    }
}
