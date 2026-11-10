package com.theoriacodex.sources.custom

import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProbeResult
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.danbooru.DanbooruSite
import com.theoriacodex.sources.gelbooru.GelbooruSite
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Finds which supported protocol family a typed address speaks (plan C3, "Probe algorithm"). Read
 * only and bounded: [deadlineMs] overall, at most [maxRequests] requests, no retries, nothing
 * persisted. Per-request timeouts and the redirect policy come from [http], a [CustomSiteHttpClient].
 */
class SiteProber(
    private val registry: SiteFamilyRegistry,
    private val http: SourceHttpClient,
    /** Host (without `www.`) → built-in source id. */
    private val builtInHosts: Map<String, String> = BUILT_IN_SITE_HOSTS,
    private val deadlineMs: Long = DEADLINE_MS,
    private val maxRequests: Int = MAX_REQUESTS,
) {
    /**
     * With [credentials] the keys are what get tested, so a key works even where anonymous access is closed
     * and a bad one is reported instead of saved unseen. [knownFamily] is a saved site's: it is tried first,
     * and no anonymous request is made, because only adding a site needs to know if it works without keys.
     */
    suspend fun probe(
        input: String,
        credentials: SiteCredentials? = null,
        headers: SiteHeaders = SiteHeaders(),
        knownFamily: ProtocolFamily? = null,
    ): SiteProbeResult {
        val address = when (val parsed = SiteAddress.parse(input)) {
            is SiteAddressParse.Invalid -> return SiteProbeResult.InvalidAddress(parsed.error)
            is SiteAddressParse.Valid -> parsed.address
        }
        builtInHosts[address.host.removePrefix("www.")]?.let { return SiteProbeResult.AlreadyBuiltIn(it) }
        val anonymous = SiteRequestContext(http = BudgetedHttpClient(http, maxRequests), headers = headers)
        return withTimeoutOrNull(deadlineMs) {
            if (credentials != null) {
                probeWithKeys(address, anonymous, credentials, knownFamily)
            } else {
                probeAnonymously(address, anonymous)
            }
        } ?: SiteProbeResult.Unreachable(address, ProbeFailure.TIMEOUT)
    }

    private suspend fun probeAnonymously(address: SiteAddress, anonymous: SiteRequestContext): SiteProbeResult {
        var refused: SiteProbeResult.CredentialsRequired? = null
        var failure: ProbeFailure? = null
        for (family in registry.families) {
            when (val result = family.probe(address, anonymous)) {
                is FamilyProbe.Match -> return supported(address, anonymous, family.family, result, keyed = false)
                FamilyProbe.NoMatch -> Unit
                is FamilyProbe.Failed -> failure = failure ?: result.reason
                FamilyProbe.NeedsCredentials ->
                    refused = refused ?: SiteProbeResult.CredentialsRequired(address, family.family, rejected = false)
            }
        }
        return unclaimedResult(address, anonymous, refused, failure)
    }

    private suspend fun probeWithKeys(
        address: SiteAddress,
        anonymous: SiteRequestContext,
        credentials: SiteCredentials,
        knownFamily: ProtocolFamily?,
    ): SiteProbeResult {
        val keyed = anonymous.copy(credentials = credentials)
        var refused: SiteProbeResult.CredentialsRequired? = null
        var failure: ProbeFailure? = null
        for (family in registry.families.sortedBy { it.family != knownFamily }) {
            when (val result = family.probe(address, keyed)) {
                is FamilyProbe.Match -> {
                    val required = knownFamily == null && family.probe(address, anonymous).refusesAnonymousUse()
                    return supported(address, anonymous, family.family, result, keyed = required)
                }
                FamilyProbe.NoMatch -> Unit
                is FamilyProbe.Failed -> failure = failure ?: result.reason
                FamilyProbe.NeedsCredentials ->
                    refused = refused ?: SiteProbeResult.CredentialsRequired(address, family.family, rejected = true)
            }
        }
        return unclaimedResult(address, anonymous, refused, failure)
    }

    private fun FamilyProbe.refusesAnonymousUse(): Boolean =
        this == FamilyProbe.NeedsCredentials || this == FamilyProbe.Failed(ProbeFailure.BLOCKED)

    /** What a probe reports when no family matched. */
    private suspend fun unclaimedResult(
        address: SiteAddress,
        anonymous: SiteRequestContext,
        refused: SiteProbeResult.CredentialsRequired?,
        failure: ProbeFailure?,
    ): SiteProbeResult {
        registry.unsupported.firstOrNull { it.matches(address, anonymous) }
            ?.let { return SiteProbeResult.UnsupportedFamily(address, it.displayName) }
        return refused
            ?: failure?.let { SiteProbeResult.Unreachable(address, it) }
            ?: SiteProbeResult.NotABooru(address)
    }

    private suspend fun supported(
        address: SiteAddress,
        context: SiteRequestContext,
        family: ProtocolFamily,
        match: FamilyProbe.Match,
        keyed: Boolean,
    ): SiteProbeResult.Supported {
        val home = homePage(address, context)
        return SiteProbeResult.Supported(
            address = address,
            family = family,
            suggestedLabel = match.suggestedLabel ?: home?.name ?: defaultLabel(address),
            searchTermLimit = match.searchTermLimit,
            credentialsRequired = keyed,
            iconUrl = home?.iconUrl,
        )
    }

    companion object {
        const val DEADLINE_MS = 30_000L

        /**
         * Worst case is 8: a keyed Danbooru match and its anonymous repeat (three requests each), the
         * home page, and the conventional touch icon.
         */
        const val MAX_REQUESTS = 8

        /** Shipped booru sites, from the family presets, including separate API hosts. */
        val BUILT_IN_SITE_HOSTS: Map<String, String> = buildMap {
            listOf(DanbooruSite.DANBOORU, DanbooruSite.AIBOORU).forEach { put(hostOf(it.baseUrl), it.source.id) }
            listOf(GelbooruSite.GELBOORU, GelbooruSite.RULE34XXX).forEach { site ->
                put(hostOf(site.siteUrl), site.source.id)
                put(hostOf(site.apiUrl), site.source.id)
            }
        }

        /** The host without `www.`, cut to the label limit: "safebooru.org". */
        fun defaultLabel(address: SiteAddress): String =
            address.host.removePrefix("www.").take(SiteProfile.MAX_LABEL_LENGTH).trimEnd('.', '-')

        private fun hostOf(url: String): String =
            (SiteAddress.parse(url) as SiteAddressParse.Valid).address.host.removePrefix("www.")
    }
}

/** What the home page says about the site; either part may be missing. [iconSize] is in pixels. */
internal data class HomePage(val name: String?, val iconUrl: String?, val iconSize: Int = 0)

/**
 * Best effort: any failure leaves the host as the label and `/favicon.ico` as the icon. When the
 * declared icon is small, tries the conventional `/apple-touch-icon.png` (180px), which many sites
 * serve without declaring it.
 */
private suspend fun homePage(address: SiteAddress, context: SiteRequestContext): HomePage? {
    val home = runCatchingPreservingCancellation {
        val response = context.get(address, "/")
        response.body.takeIf { response.statusCode == 200 }?.let { parseHomePage(it, "${address.baseUrl}/") }
    }.getOrNull()
    if (home != null && home.iconSize >= LARGE_ICON_PX) return home
    val touchIcon = runCatchingPreservingCancellation {
        val response = context.get(address, APPLE_TOUCH_ICON_PATH)
        val type = response.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
        "${address.baseUrl}$APPLE_TOUCH_ICON_PATH".takeIf { response.statusCode == 200 && type?.startsWith("image/") == true }
    }.getOrNull()
    return when {
        touchIcon != null -> (home ?: HomePage(null, null)).copy(iconUrl = touchIcon, iconSize = APPLE_TOUCH_ICON_PX)
        else -> home
    }
}

internal fun parseHomePage(html: String, pageUrl: String): HomePage {
    val document = Jsoup.parse(html, pageUrl)
    val icon = siteIcon(document)
    return HomePage(siteName(document), icon?.first, icon?.second ?: 0)
}

private const val APPLE_TOUCH_ICON_PATH = "/apple-touch-icon.png"
private const val APPLE_TOUCH_ICON_PX = 180
private const val LARGE_ICON_PX = 64

/**
 * The largest declared raster icon over HTTPS. Apple touch icons are 180px when unsized; a sizeless
 * `icon` is assumed small. SVG is skipped because media loads don't decode it.
 */
private fun siteIcon(document: Document): Pair<String, Int>? =
    document.select("link[rel~=(?i)^(icon|shortcut icon|apple-touch-icon|apple-touch-icon-precomposed)$][href]")
        .mapNotNull { link ->
            val url = link.absUrl("href").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            if (url.substringBefore('?').endsWith(".svg", ignoreCase = true) || link.attr("type").contains("svg")) {
                return@mapNotNull null
            }
            val declared = link.attr("sizes").split(' ').mapNotNull { it.lowercase().substringBefore('x').toIntOrNull() }.maxOrNull()
            val size = declared ?: if (link.attr("rel").contains("apple", ignoreCase = true)) 180 else 16
            url to size
        }
        .maxByOrNull { it.second }

/**
 * `og:site_name` when present, else the `<title>` up to its first separator ("Gelbooru | Anime and
 * Hentai Imageboard" → "Gelbooru"). Null when neither gives a usable name.
 */
internal fun siteNameFromHtml(html: String): String? = siteName(Jsoup.parse(html))

private fun siteName(document: Document): String? {
    val ogName = document.selectFirst("meta[property=og:site_name]")?.attr("content")
    val title = document.title().split(" | ", " - ", " – ", " — ", " :: ").first()
    return listOf(ogName, title).firstNotNullOfOrNull { candidate ->
        candidate?.trim()?.take(SiteProfile.MAX_LABEL_LENGTH)?.trim()?.takeIf(String::isNotEmpty)
    }
}

/** Fails any request past [maxRequests], so a probe can never fan out. */
private class BudgetedHttpClient(private val delegate: SourceHttpClient, private val maxRequests: Int) : SourceHttpClient {
    private val sent = AtomicInteger()

    override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
        if (sent.incrementAndGet() > maxRequests) throw IOException("Probe request budget exhausted")
        return delegate.get(url, query, headers)
    }

    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
        throw UnsupportedOperationException("Probes never write")
}
