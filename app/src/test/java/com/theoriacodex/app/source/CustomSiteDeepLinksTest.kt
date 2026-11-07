package com.theoriacodex.app.source

import com.theoriacodex.app.customsites.CustomSiteAvailability
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomSiteDeepLinksTest {
    private val gelbooru = site("custom_safebooru_org", "Safebooru", "https://safebooru.org", ProtocolFamily.GELBOORU_02)
    private val danbooru = site("custom_booru_example", "Example", "https://booru.example/sub", ProtocolFamily.DANBOORU)
    private val sites = listOf(gelbooru, danbooru)

    @Test fun `matches each family's post page on an added site`() {
        assertEquals(
            ExternalPostDeepLink(SourceKey.parse(gelbooru.sourceId)!!, "Safebooru", "42"),
            parseCustomSitePostDeepLink("https://www.safebooru.org/index.php?page=post&s=view&id=42", sites),
        )
        assertEquals("7", parseCustomSitePostDeepLink("https://booru.example/sub/posts/7", sites)?.postId)
    }

    @Test fun `ignores other pages, other install paths and other hosts`() {
        assertNull(parseCustomSitePostDeepLink("https://safebooru.org/index.php?page=post&s=list", sites))
        assertNull(parseCustomSitePostDeepLink("https://booru.example/posts/7", sites))
        assertNull(parseCustomSitePostDeepLink("https://other.example/posts/7", sites))
    }

    @Test fun `offers setup only for a post link on a site that isn't added`() {
        assertEquals("new.example", unaddedBooruPostHost("https://new.example/posts/3", sites))
        assertNull(unaddedBooruPostHost("https://safebooru.org/index.php?page=post&s=view&id=1", sites))
        assertNull(unaddedBooruPostHost("https://new.example/about", sites))
    }

    private fun site(id: String, label: String, baseUrl: String, family: ProtocolFamily) = CustomSiteSummary(
        sourceId = id,
        label = label,
        host = baseUrl.removePrefix("https://").substringBefore('/'),
        baseUrl = baseUrl,
        family = family,
        availability = CustomSiteAvailability.AVAILABLE,
        headers = SiteHeaders(),
        hasCredentials = false,
        includedInUnifiedAndForYou = false,
        verifiedAtEpochMs = null,
    )
}
