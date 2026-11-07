package com.theoriacodex.sources.custom

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.credentials.UserApiKeyCredentials
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.http.THEORIA_SOURCE_USER_AGENT
import java.io.IOException

// Protocol families for user-added sites (plan contract C3). Families recognize a site from
// protocol evidence that also exists on an empty or restricted site, never from a parsed post.

/** What a family needs to talk to one site. [http] should be a [CustomSiteHttpClient]. */
data class SiteRequestContext(
    val http: SourceHttpClient,
    val headers: SiteHeaders = SiteHeaders(),
    val credentials: SiteCredentials? = null,
) {
    /** API headers: the site's User-Agent override or Theoria's agent, plus an optional Referer. */
    val requestHeaders: Map<String, String>
        get() = buildMap {
            put("User-Agent", headers.userAgent ?: THEORIA_SOURCE_USER_AGENT)
            headers.referer?.let { put("Referer", it) }
        }

    /** GET [path] under [address] with this context's headers; credentials are added by the caller. */
    suspend fun get(address: SiteAddress, path: String, query: Map<String, String> = emptyMap()): SourceHttpResponse =
        http.get(url = address.baseUrl + path, query = query, headers = requestHeaders)
}

/** One supported booru API dialect: how to recognize it and how to build its adapter. */
interface SiteFamily {
    val family: ProtocolFamily

    /** A few bounded, read-only requests; transport failures come back as [FamilyProbe.Failed]. */
    suspend fun probe(address: SiteAddress, context: SiteRequestContext): FamilyProbe

    /**
     * The family adapter for a stored profile. [source] must be the profile's identity; custom
     * identities exist once the open `SourceKey` lands (plan B1), until then only tests call this.
     */
    fun createAdapter(profile: SiteProfile, source: SourceKey, context: SiteRequestContext): SourceAdapter
}

sealed interface FamilyProbe {
    /** [searchTermLimit]: measured or the family default; null means unlimited. */
    data class Match(val suggestedLabel: String?, val searchTermLimit: Int?) : FamilyProbe

    /** The API answered as this family but refused this access. */
    data object NeedsCredentials : FamilyProbe

    /** A well-formed answer that is not this family. */
    data object NoMatch : FamilyProbe

    /** Inconclusive: the site could not answer (transport failure, challenge page, server error). */
    data class Failed(val reason: ProbeFailure) : FamilyProbe
}

/** A recognized booru API Theoria cannot use yet; detection only. */
interface UnsupportedFamilySignature {
    val displayName: String

    /** Transport failures count as no match. */
    suspend fun matches(address: SiteAddress, context: SiteRequestContext): Boolean
}

/** Supported families in probe order, then signatures of unsupported ones. */
class SiteFamilyRegistry(
    val families: List<SiteFamily>,
    val unsupported: List<UnsupportedFamilySignature>,
) {
    fun familyFor(family: ProtocolFamily): SiteFamily? = families.firstOrNull { it.family == family }

    companion object {
        /** Danbooru first: its evidence endpoint is cheap and Gelbooru sites answer it with a plain 404. */
        fun default(): SiteFamilyRegistry = SiteFamilyRegistry(
            families = listOf(DanbooruFamily, GelbooruFamily),
            unsupported = listOf(MoebooruSignature),
        )
    }
}

/** Site keys in the shape both families' adapters accept. */
internal fun SiteCredentials.asUserApiKey(): UserApiKeyCredentials = object : UserApiKeyCredentials {
    override val userId: String = user
    override val apiKey: String = this@asUserApiKey.apiKey
}

/** Runs one family probe, turning transport failures into [FamilyProbe.Failed]. */
internal suspend fun probing(block: suspend () -> FamilyProbe): FamilyProbe = try {
    block()
} catch (error: IOException) {
    FamilyProbe.Failed(probeFailureOf(error))
}
