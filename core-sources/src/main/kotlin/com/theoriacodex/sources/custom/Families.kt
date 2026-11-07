package com.theoriacodex.sources.custom

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.SearchTermLimit
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.credentials.ApiKeyUse
import com.theoriacodex.sources.danbooru.DanbooruSite
import com.theoriacodex.sources.danbooru.DanbooruSourceAdapter
import com.theoriacodex.sources.gelbooru.GelbooruSite
import com.theoriacodex.sources.gelbooru.GelbooruSourceAdapter
import com.theoriacodex.sources.gelbooru.GelbooruTagList
import java.io.IOException

/**
 * Danbooru 2 (`/posts.json`). Evidence: `/counts/posts.json` answers `{"counts":{"posts":N}}`, or
 * the family's JSON error envelope when access is refused. On a match, `/profile.json` measures
 * the search term limit for this access (AIBooru guests: 4), then the adapter reads a page.
 */
object DanbooruFamily : SiteFamily {
    override val family = ProtocolFamily.DANBOORU

    /** Danbooru's own guest limit, used when `/profile.json` gives none. */
    private const val DEFAULT_TERM_LIMIT = 2

    override suspend fun probe(address: SiteAddress, context: SiteRequestContext): FamilyProbe = probing {
        val counts = context.get(address, "/counts/posts.json", context.danbooruAuth())
        when {
            counts.isDanbooruCounts() ->
                verifyWithAdapter(address, context, FamilyProbe.Match(suggestedLabel = null, searchTermLimit = measureTermLimit(address, context)))
            counts.isDanbooruAuthRefusal() -> FamilyProbe.NeedsCredentials
            else -> counts.unclaimed()
        }
    }

    /** Optional: a failed profile request falls back to the default instead of failing the probe. */
    private suspend fun measureTermLimit(address: SiteAddress, context: SiteRequestContext): Int {
        val measured = try {
            context.get(address, "/profile.json", context.danbooruAuth()).danbooruTagQueryLimit()
        } catch (_: IOException) {
            null
        }
        return measured ?: DEFAULT_TERM_LIMIT
    }

    override fun createAdapter(profile: SiteProfile, source: SourceKey, context: SiteRequestContext): SourceAdapter =
        DanbooruSourceAdapter(
            site = DanbooruSite(
                source = source,
                label = profile.label,
                baseUrl = profile.address.baseUrl,
                // An `order:` metatag counts against Danbooru's limit, as on the built-in site.
                searchTermLimit = profile.searchTermLimit?.let { SearchTermLimit(maxTerms = it, rankedSortUsesTerm = true) },
                headers = context.requestHeaders,
                credentials = profile.apiKeyUse,
            ),
            httpClient = context.http,
            credentials = { context.credentials?.asUserApiKey() },
        )

    private fun SiteRequestContext.danbooruAuth(): Map<String, String> =
        credentials?.let { mapOf("login" to it.user, "api_key" to it.apiKey) }.orEmpty()
}

/**
 * Gelbooru 0.2 DAPI (`index.php?page=dapi`). Evidence: the DAPI without `json=1` answers a
 * `<posts count="N">` envelope, or a keyless refusal in Gelbooru's or rule34.xxx's shape. The
 * adapter reads `json=1`, so a match also needs the adapter to read a page.
 */
object GelbooruFamily : SiteFamily {
    override val family = ProtocolFamily.GELBOORU_02

    private val POST_LIST = mapOf("page" to "dapi", "s" to "post", "q" to "index")

    override suspend fun probe(address: SiteAddress, context: SiteRequestContext): FamilyProbe = probing {
        val auth = context.gelbooruAuth()
        val envelope = context.get(address, "/index.php", POST_LIST + ("limit" to "1") + auth)
        when {
            envelope.isGelbooruPostsEnvelope() ->
                verifyWithAdapter(address, context, FamilyProbe.Match(suggestedLabel = null, searchTermLimit = null))
            envelope.isGelbooruAuthRefusal() -> FamilyProbe.NeedsCredentials
            else -> envelope.unclaimed()
        }
    }

    /**
     * A conservative profile for an unknown fork: OR groups are expanded by the search orchestrator
     * (forks disagree on `{a ~ b}` versus `( a ~ b )`), the tag list format is learned on first use,
     * and Gelbooru-only extras (related posts, uploader pages, samples first) stay off.
     */
    override fun createAdapter(profile: SiteProfile, source: SourceKey, context: SiteRequestContext): SourceAdapter =
        GelbooruSourceAdapter(
            site = GelbooruSite(
                source = source,
                label = profile.label,
                siteUrl = profile.address.baseUrl,
                headers = context.requestHeaders,
                credentials = profile.apiKeyUse,
                tagList = GelbooruTagList.DETECT,
            ),
            httpClient = context.http,
            credentials = { context.credentials?.asUserApiKey() },
        )

    private fun SiteRequestContext.gelbooruAuth(): Map<String, String> =
        credentials?.let { mapOf("user_id" to it.user, "api_key" to it.apiKey) }.orEmpty()
}

/** Moebooru (`/post.json`), e.g. Sakugabooru and Konachan. Detected so the UI can name it. */
object MoebooruSignature : UnsupportedFamilySignature {
    override val displayName = "Moebooru"

    override suspend fun matches(address: SiteAddress, context: SiteRequestContext): Boolean = try {
        context.get(address, "/post.json", mapOf("limit" to "1")).isMoebooruPostList()
    } catch (_: IOException) {
        false
    }
}

/**
 * The probe's second, optional check (plan C3): the family adapter reads the newest page under the
 * identity the site would get, so a locked-in family is one the real adapter can read. An empty
 * page passes (an empty site, or Danbooru hiding restricted posts); an unreadable one is not this
 * family. Keys in [context] are sent as a saved site would send them.
 */
private suspend fun SiteFamily.verifyWithAdapter(
    address: SiteAddress,
    context: SiteRequestContext,
    match: FamilyProbe.Match,
): FamilyProbe {
    val profile = SiteProfile(
        sourceId = address.derivedSourceId,
        address = address,
        label = SiteProber.defaultLabel(address),
        familyId = family.name,
        searchTermLimit = match.searchTermLimit,
        addedAtEpochMs = 0,
    )
    val source = SourceKey.of(profile)
    val newest = Query(
        mode = QueryMode.Source(source),
        includeTags = emptyList(),
        excludeTags = emptyList(),
        sort = SortMode.NEWEST,
        dateRange = null,
        minScore = null,
    )
    return try {
        createAdapter(profile, source, context).search(newest, pageToken = null)
        match
    } catch (error: SourceAdapterException) {
        when (error.reason) {
            SourceFailureReason.AUTH_REQUIRED, SourceFailureReason.AUTH_EXPIRED -> FamilyProbe.NeedsCredentials
            SourceFailureReason.NETWORK -> FamilyProbe.Failed((error.cause as? IOException)?.let(::probeFailureOf) ?: ProbeFailure.HTTP_ERROR)
            SourceFailureReason.RATE_LIMITED -> FamilyProbe.Failed(ProbeFailure.HTTP_ERROR)
            SourceFailureReason.PARSE, SourceFailureReason.UNKNOWN -> FamilyProbe.NoMatch
        }
    }
}

private val SiteProfile.apiKeyUse: ApiKeyUse
    get() = if (credentialsRequired) ApiKeyUse.REQUIRED else ApiKeyUse.OPTIONAL
