package com.theoriacodex.app.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.hitomi.HitomiMediaCandidate
import com.theoriacodex.sources.hitomi.HitomiMediaFormat
import com.theoriacodex.sources.hitomi.HitomiProtocol
import com.theoriacodex.sources.hitomi.HitomiSourceAdapter
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HitomiProtocolDeviceTest {
    @Test
    fun galleryAssignmentParserIsCompatibleWithAndroidRuntime() {
        val gallery = HitomiProtocol.parseGalleryAssignment(
            """
            const galleryinfo = {
              "id": 4042375,
              "type": "artistcg"
            };
            """.trimIndent(),
        )

        assertEquals(4_042_375, gallery.get("id").asInt)
        assertEquals("artistcg", gallery.get("type").asString)
    }

    @Test
    fun liveTypedAndMultiTagSearchesUseAndroidByteRangeTransport() = runBlocking {
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
            val gameCg = adapter.search(
                Query(
                    mode = QueryMode.Source(SourceKey.HITOMI),
                    includeTerms = listOf(SearchTerm("gamecg", SearchFacet.TYPE, "type")),
                    excludeTerms = emptyList(),
                    sort = SortMode.NEWEST,
                    dateRange = null,
                    minScore = null,
                ),
                null,
            )
            assertEquals(2, gameCg.items.size)

            val multiTag = adapter.search(
                Query(
                    mode = QueryMode.Source(SourceKey.HITOMI),
                    includeTerms = listOf(
                        SearchTerm("animated", SearchFacet.TAG, "tag"),
                        SearchTerm("x-ray", SearchFacet.TAG, "female"),
                    ),
                    excludeTerms = emptyList(),
                    sort = SortMode.NEWEST,
                    dateRange = null,
                    minScore = null,
                ),
                null,
            )
            assertTrue(multiTag.items.isNotEmpty())
        }
    }
}
