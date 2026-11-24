package com.theoriacodex.sources.custom

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.credentials.UserApiKey
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.http.THEORIA_SOURCE_USER_AGENT
import java.io.IOException

data class SiteRequestContext(
    val http: SourceHttpClient,
    val headers: SiteHeaders = SiteHeaders(),
    val credentials: SiteCredentials? = null,
) {
    val requestHeaders: Map<String, String>
        get() = buildMap {
            put("User-Agent", headers.userAgent ?: THEORIA_SOURCE_USER_AGENT)
            headers.referer?.let { put("Referer", it) }
        }

    suspend fun get(address: SiteAddress, path: String, query: Map<String, String> = emptyMap()): SourceHttpResponse =
        http.get(url = address.baseUrl + path, query = query, headers = requestHeaders)
}

interface SiteFamily {
    val family: ProtocolFamily

    suspend fun probe(address: SiteAddress, context: SiteRequestContext): FamilyProbe

    fun createAdapter(profile: SiteProfile, source: SourceKey, context: SiteRequestContext): SourceAdapter
}

sealed interface FamilyProbe {
    data class Match(val suggestedLabel: String?, val searchTermLimit: Int?) : FamilyProbe

    data object NeedsCredentials : FamilyProbe

    data object NoMatch : FamilyProbe

    data class Failed(val reason: ProbeFailure) : FamilyProbe
}

interface UnsupportedFamilySignature {
    val displayName: String

    suspend fun matches(address: SiteAddress, context: SiteRequestContext): Boolean
}

class SiteFamilyRegistry(
    val families: List<SiteFamily>,
    val unsupported: List<UnsupportedFamilySignature>,
) {
    fun familyFor(family: ProtocolFamily): SiteFamily? = families.firstOrNull { it.family == family }

    companion object {
        fun default(): SiteFamilyRegistry = SiteFamilyRegistry(
            families = listOf(DanbooruFamily, GelbooruFamily),
            unsupported = listOf(MoebooruSignature),
        )
    }
}

internal fun SiteCredentials.asUserApiKey(): UserApiKey = UserApiKey(userId = user, apiKey = apiKey)

internal suspend fun probing(block: suspend () -> FamilyProbe): FamilyProbe = try {
    block()
} catch (error: IOException) {
    FamilyProbe.Failed(probeFailureOf(error))
}
