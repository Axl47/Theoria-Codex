package com.theoriacodex.sources.custom

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.Query
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

object DanbooruFamily : SiteFamily {
    override val family = ProtocolFamily.DANBOORU

    private const val DEFAULT_TERM_LIMIT = 2

    override suspend fun probe(address: SiteAddress, context: SiteRequestContext): FamilyProbe = probing {
        val counts = context.get(address, "/counts/posts.json", DanbooruSite.authQuery(context.credentials?.asUserApiKey()))
        when {
            counts.isDanbooruCounts() ->
                verifyWithAdapter(address, context, FamilyProbe.Match(suggestedLabel = null, searchTermLimit = measureTermLimit(address, context)))
            counts.isDanbooruAuthRefusal() -> FamilyProbe.NeedsCredentials
            else -> counts.unclaimed()
        }
    }

    private suspend fun measureTermLimit(address: SiteAddress, context: SiteRequestContext): Int {
        val measured = try {
            context.get(address, "/profile.json", DanbooruSite.authQuery(context.credentials?.asUserApiKey())).danbooruTagQueryLimit()
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
                searchTermLimit = profile.searchTermLimit?.let { SearchTermLimit(maxTerms = it, rankedSortUsesTerm = true) },
                headers = context.requestHeaders,
                credentials = profile.apiKeyUse,
            ),
            httpClient = context.http,
            credentials = { context.credentials?.asUserApiKey() },
        )
}

object GelbooruFamily : SiteFamily {
    override val family = ProtocolFamily.GELBOORU_02

    private val POST_LIST = mapOf("page" to "dapi", "s" to "post", "q" to "index")

    override suspend fun probe(address: SiteAddress, context: SiteRequestContext): FamilyProbe = probing {
        val auth = GelbooruSite.authQuery(context.credentials?.asUserApiKey())
        val envelope = context.get(address, "/index.php", POST_LIST + ("limit" to "1") + auth)
        when {
            envelope.isGelbooruPostsEnvelope() ->
                verifyWithAdapter(address, context, FamilyProbe.Match(suggestedLabel = null, searchTermLimit = null))
            envelope.isGelbooruAuthRefusal() -> FamilyProbe.NeedsCredentials
            else -> envelope.unclaimed()
        }
    }

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
}

object MoebooruSignature : UnsupportedFamilySignature {
    override val displayName = "Moebooru"

    override suspend fun matches(address: SiteAddress, context: SiteRequestContext): Boolean = try {
        context.get(address, "/post.json", mapOf("limit" to "1")).isMoebooruPostList()
    } catch (_: IOException) {
        false
    }
}

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
    val newest = Query.newest(source)
    return try {
        createAdapter(profile, source, context).search(newest, pageToken = null)
        match
    } catch (error: SourceAdapterException) {
        when (error.reason) {
            SourceFailureReason.AUTH_REQUIRED, SourceFailureReason.AUTH_EXPIRED -> FamilyProbe.NeedsCredentials
            SourceFailureReason.NETWORK, SourceFailureReason.TIMED_OUT -> FamilyProbe.Failed((error.cause as? IOException)?.let(::probeFailureOf) ?: ProbeFailure.HTTP_ERROR)
            SourceFailureReason.RATE_LIMITED, SourceFailureReason.BLOCKED -> FamilyProbe.Failed(ProbeFailure.HTTP_ERROR)
            SourceFailureReason.PARSE, SourceFailureReason.UNKNOWN -> FamilyProbe.NoMatch
        }
    }
}

private val SiteProfile.apiKeyUse: ApiKeyUse
    get() = if (credentialsRequired) ApiKeyUse.REQUIRED else ApiKeyUse.OPTIONAL
