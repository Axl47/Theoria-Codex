package com.theoriacodex.sources.custom

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.SearchTermLimit
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.testing.ScriptedHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapters built from stored profiles. Built-in keys stand in for custom identities until the open
 * `SourceKey` lands (plan B1).
 */
class FamilyAdapterTest {
    private val http = ScriptedHttpClient()

    @Test
    fun `a custom Danbooru site sends its keys, headers and term limit`() = runTest {
        val context = SiteRequestContext(http, SiteHeaders(userAgent = "Custom/1.0", referer = "https://booru.example/"), SiteCredentials("alice", "k1"))
        val adapter = DanbooruFamily.createAdapter(profile(ProtocolFamily.DANBOORU, termLimit = 4), SourceKey.DANBOORU, context)
        http.enqueueGet(
            url = "https://booru.example/posts/7.json",
            query = mapOf("login" to "alice", "api_key" to "k1"),
            requiredHeaders = mapOf("User-Agent" to "Custom/1.0", "Referer" to "https://booru.example/"),
            body = """{"id":7,"file_url":"http://cdn.booru.example/7.jpg","large_file_url":"//cdn.booru.example/s/7.jpg","tag_string":"a"}""",
        )

        val post = adapter.resolvePost(PostId(SourceKey.DANBOORU, "7"))!!

        assertEquals(SearchTermLimit(maxTerms = 4, rankedSortUsesTerm = true), adapter.capabilities.searchTermLimit)
        assertEquals("https://cdn.booru.example/7.jpg", post.full?.url)
        assertEquals(listOf("https://cdn.booru.example/s/7.jpg"), post.full?.progressiveUrls)
        http.assertExhausted()
    }

    @Test
    fun `a keyed site without saved keys fails before any request`() = runTest {
        val profile = profile(ProtocolFamily.GELBOORU_02, credentialsRequired = true)
        val adapter = GelbooruFamily.createAdapter(profile, SourceKey.GELBOORU, SiteRequestContext(http))

        val failure = runCatching { adapter.search(query(), null) }.exceptionOrNull()

        assertEquals(SourceFailureReason.AUTH_REQUIRED, (failure as SourceAdapterException).reason)
        assertTrue(adapter.capabilities.requiresCredentials)
        http.assertExhausted()
    }

    @Test
    fun `a custom Gelbooru site expands OR groups client-side and sends keys as user_id`() = runTest {
        val adapter = GelbooruFamily.createAdapter(
            profile(ProtocolFamily.GELBOORU_02),
            SourceKey.GELBOORU,
            SiteRequestContext(http, credentials = SiteCredentials("42", "k2")),
        )
        http.enqueueGet(
            url = "https://booru.example/index.php",
            query = mapOf(
                "page" to "dapi", "json" to "1", "s" to "post", "q" to "index", "pid" to "0", "limit" to "40",
                "tags" to "sky", "user_id" to "42", "api_key" to "k2",
            ),
            requiredHeaders = mapOf("User-Agent" to com.theoriacodex.sources.http.THEORIA_SOURCE_USER_AGENT),
            body = "",
        )

        adapter.search(query(), null)

        assertEquals(false, adapter.capabilities.supportsGroupedIncludeTagsServerSide)
        http.assertExhausted()
    }

    @Test
    fun `an XML-only tag list switches suggestions and counts to autocomplete once`() = runTest {
        val adapter = GelbooruFamily.createAdapter(profile(ProtocolFamily.GELBOORU_02), SourceKey.GELBOORU, SiteRequestContext(http))
        val safebooruTagList = FixtureSiteTransport.resource("safebooru/tag_list_ignores_json.xml").decodeToString()
        val autocomplete = """[{"label":"sky (355769)","value":"sky"},{"label":"skyline (2705)","value":"skyline"}]"""
        http.enqueueGet(
            url = "https://booru.example/index.php",
            query = mapOf(
                "page" to "dapi", "json" to "1", "s" to "tag", "q" to "index", "name_pattern" to "sky%",
                "orderby" to "count", "order" to "desc", "limit" to "10",
            ),
            body = safebooruTagList,
        )
        http.enqueueGet(url = "https://booru.example/autocomplete.php", query = mapOf("q" to "sky"), body = autocomplete)
        http.enqueueGet(url = "https://booru.example/autocomplete.php", query = mapOf("q" to "sky"), body = autocomplete)

        val suggestions = adapter.autocompleteTags("sky", limit = 10)
        val counts = (adapter as TagCountLookupSourceAdapter).fetchTagCounts(listOf("sky"))
        val trending = adapter.trendingTags(limit = 10)

        assertEquals(listOf("sky", "skyline"), suggestions.map { it.text })
        assertEquals(mapOf("sky" to 355_769), counts)
        assertEquals(emptyList<Any>(), trending)
        http.assertExhausted()
    }

    private fun profile(family: ProtocolFamily, termLimit: Int? = null, credentialsRequired: Boolean = false): SiteProfile {
        val address = (SiteAddress.parse("booru.example") as SiteAddressParse.Valid).address
        return SiteProfile(
            sourceId = address.derivedSourceId,
            address = address,
            label = "Booru",
            familyId = family.name,
            searchTermLimit = termLimit,
            credentialsRequired = credentialsRequired,
            addedAtEpochMs = 0,
        )
    }

    private fun query() = Query(
        mode = QueryMode.Source(SourceKey.GELBOORU),
        includeTags = listOf("sky"),
        excludeTags = emptyList(),
        sort = SortMode.NEWEST,
        dateRange = null,
        minScore = null,
    )
}
