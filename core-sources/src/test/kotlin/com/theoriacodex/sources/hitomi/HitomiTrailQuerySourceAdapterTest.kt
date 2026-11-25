package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

internal class HitomiTrailQuerySourceAdapterTest : HitomiSourceAdapterTestFixture() {
    @Test
    fun `a trail step's tag ANDs with the earlier typed terms and keeps every gallery in both`() = runTest {
        val version = "12345"
        val seriesUrl = HitomiNozomi.urlFor(HitomiNozomiRequest(area = "series", tag = "black souls", language = "all"))
        val http = RoutingHitomiHttpClient().apply {
            textRoutes["https://ltn.gold-usergeneratedcontent.net/galleriesindex/version"] = version
            routeGlobalIndex(version, "animated", listOf(5, 3, 1))
            binaryIndexes[seriesUrl] = listOf(5, 4, 3, 2)
        }
        val adapter = adapter(http, pageSize = 10)

        val page = adapter.search(
            query(include = listOf(SearchTerm("animated"), SearchTerm("black souls", SearchFacet.SERIES, "series"))),
            null,
        )

        assertEquals(listOf("5", "3"), page.items.map { post -> post.id.sourcePostId })
    }
}
