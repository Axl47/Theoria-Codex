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
    suspend fun probe(
        input: String,
        credentials: SiteCredentials? = null,
        headers: SiteHeaders = SiteHeaders(),
    ): SiteProbeResult {
        val address = when (val parsed = SiteAddress.parse(input)) {
            is SiteAddressParse.Invalid -> return SiteProbeResult.InvalidAddress(parsed.error)
            is SiteAddressParse.Valid -> parsed.address
        }
        builtInHosts[address.host.removePrefix("www.")]?.let { return SiteProbeResult.AlreadyBuiltIn(it) }
        val anonymous = SiteRequestContext(http = BudgetedHttpClient(http, maxRequests), headers = headers)
        return withTimeoutOrNull(deadlineMs) { probeFamilies(address, anonymous, credentials) }
            ?: SiteProbeResult.Unreachable(address, ProbeFailure.TIMEOUT)
    }

    private suspend fun probeFamilies(
        address: SiteAddress,
        anonymous: SiteRequestContext,
        credentials: SiteCredentials?,
    ): SiteProbeResult {
        var refused: SiteProbeResult.CredentialsRequired? = null
        var failure: ProbeFailure? = null
        for (family in registry.families) {
            when (val result = family.probe(address, anonymous)) {
                is FamilyProbe.Match -> return supported(address, anonymous, family.family, result, keyed = false)
                FamilyProbe.NoMatch -> Unit
                is FamilyProbe.Failed -> failure = failure ?: result.reason
                FamilyProbe.NeedsCredentials -> {
                    // Anonymous first; supplied keys are tried once, and only on a family that refused.
                    val withKeys = credentials?.let { family.probe(address, anonymous.copy(credentials = it)) }
                    if (withKeys is FamilyProbe.Match) {
                        return supported(address, anonymous, family.family, withKeys, keyed = true)
                    }
                    refused = refused ?: SiteProbeResult.CredentialsRequired(address, family.family, rejected = withKeys != null)
                }
            }
        }
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
    ) =
        SiteProbeResult.Supported(
            address = address,
            family = family,
            suggestedLabel = match.suggestedLabel ?: homePageName(address, context) ?: defaultLabel(address),
            searchTermLimit = match.searchTermLimit,
            credentialsRequired = keyed,
        )

    companion object {
        const val DEADLINE_MS = 30_000L

        /** Worst case is 6: two families refusing then retried with keys, a signature or adapter page, and the home page. */
        const val MAX_REQUESTS = 7

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

/** The site's own name from its home page, best effort: any failure leaves the host as the label. */
private suspend fun homePageName(address: SiteAddress, context: SiteRequestContext): String? =
    runCatchingPreservingCancellation {
        val response = context.get(address, "/")
        response.body.takeIf { response.statusCode == 200 }?.let(::siteNameFromHtml)
    }.getOrNull()

/**
 * `og:site_name` when present, else the `<title>` up to its first separator ("Gelbooru | Anime and
 * Hentai Imageboard" → "Gelbooru"). Null when neither gives a usable name.
 */
internal fun siteNameFromHtml(html: String): String? {
    val document = Jsoup.parse(html)
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
