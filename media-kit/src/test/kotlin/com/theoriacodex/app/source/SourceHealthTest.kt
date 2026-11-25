package com.theoriacodex.app.source

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.HostOutage
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostOutageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHealthTest {
    private val sources = listOf(SourceKey.DANBOORU, SourceKey.GELBOORU, SourceKey.PIXIV)

    @Test fun `outages map to the source whose site or API host they are on, and expire`() {
        val outages = mapOf(
            "danbooru.donmai.us" to HostOutage(HostOutageKind.MAINTENANCE, untilEpochMs = 2_000),
            "api.gelbooru.com" to HostOutage(HostOutageKind.CHALLENGE, untilEpochMs = 500),
            "cdn.donmai.us" to HostOutage(HostOutageKind.MAINTENANCE, untilEpochMs = 2_000),
        )
        assertEquals(mapOf(SourceKey.DANBOORU to HostOutageKind.MAINTENANCE), sourceIssues(outages, sources, now = 1_000))
        assertEquals(
            mapOf(SourceKey.DANBOORU to HostOutageKind.MAINTENANCE, SourceKey.GELBOORU to HostOutageKind.CHALLENGE),
            sourceIssues(outages, sources, now = 0),
        )
    }

    @Test fun `a rate limit marks a source only when it covers the source's site, as requests are held off`() {
        val nhentai = listOf(SourceKey.NHENTAI)
        val pictures = mapOf("t.nhentai.net" to HostOutage(HostOutageKind.RATE_LIMITED, untilEpochMs = 2_000))
        val site = mapOf("nhentai.net" to HostOutage(HostOutageKind.RATE_LIMITED, untilEpochMs = 2_000))

        assertEquals(emptyMap<SourceKey, HostOutageKind>(), sourceIssues(pictures, nhentai, now = 1_000))
        assertEquals(mapOf(SourceKey.NHENTAI to HostOutageKind.RATE_LIMITED), sourceIssues(site, nhentai, now = 1_000))
    }

    @Test fun `an extension source's issue comes from the host of its site or a subdomain`() {
        val extension = requireNotNull(SourceKey.parse("custom_mihon_42"))
        val outages = mapOf("api.site.test" to HostOutage(HostOutageKind.CHALLENGE, untilEpochMs = 2_000))

        assertEquals(
            mapOf(extension to HostOutageKind.CHALLENGE),
            sourceIssues(outages, sources, now = 1_000, extensionSites = mapOf(extension to setOf("site.test"))),
        )
        assertEquals(emptyMap<SourceKey, HostOutageKind>(), sourceIssues(outages, sources, now = 1_000, extensionSites = mapOf(extension to setOf("other.test"))))
    }

    @Test fun `picking a down source clears its outages so the search tries again`() {
        val tracker = HostOutageTracker()
        tracker.record("https://danbooru.donmai.us/posts.json", HostOutageKind.MAINTENANCE)
        tracker.record("https://gelbooru.com/", HostOutageKind.MAINTENANCE)
        var verified = false
        val health = SourceHealth(
            issues = mapOf(SourceKey.DANBOORU to HostOutageKind.MAINTENANCE),
            recheck = tracker::clearSource,
            verify = { verified = true },
        )
        assertFalse("A retried source is still selected", health.onPick(SourceKey.DANBOORU))
        assertEquals(setOf("gelbooru.com"), tracker.outages.value.keys)
        assertFalse(verified)
        assertFalse(health.onPick(SourceKey.PIXIV))
    }

    @Test fun `a challenged source, built-in or added, opens verification instead of being selected`() {
        val custom = SourceKey.parse("custom_gated_example")!!
        val verified = mutableListOf<SourceKey>()
        val health = SourceHealth(
            issues = mapOf(custom to HostOutageKind.CHALLENGE, SourceKey.IWARA to HostOutageKind.CHALLENGE),
            verify = { verified += it },
        )
        assertTrue(health.onPick(custom))
        assertTrue(health.onPick(SourceKey.IWARA))
        assertEquals(listOf(custom, SourceKey.IWARA), verified)
    }

    @Test
    fun `the startup check leaves out sites with a saved key`() {
        val keyed = requireNotNull(SourceKey.parse("custom_booru_example"))
        assertEquals(listOf(SourceKey.DANBOORU), startupCheckSources(listOf(SourceKey.DANBOORU, keyed), setOf(keyed.id)))
    }
}
