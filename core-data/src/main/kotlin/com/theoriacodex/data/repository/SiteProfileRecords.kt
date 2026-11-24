package com.theoriacodex.data.repository

import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SiteProfileRecord(
    @SerialName("sourceId") val sourceId: String? = null,
    @SerialName("baseUrl") val baseUrl: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("family") val family: String? = null,
    @SerialName("searchTermLimit") val searchTermLimit: Int? = null,
    @SerialName("userAgent") val userAgent: String? = null,
    @SerialName("referer") val referer: String? = null,
    @SerialName("credentialsRequired") val credentialsRequired: Boolean = false,
    @SerialName("addedAt") val addedAt: Long? = null,
    @SerialName("verifiedAt") val verifiedAt: Long? = null,
    @SerialName("iconUrl") val iconUrl: String? = null,
) {
    fun toDomainOrNull(): SiteProfile? = runCatching {
        val address = (SiteAddress.parse(baseUrl.orEmpty()) as? SiteAddressParse.Valid)?.address ?: return null
        SiteProfile(
            sourceId = sourceId.orEmpty(),
            address = address,
            label = label.orEmpty(),
            familyId = family.orEmpty(),
            searchTermLimit = searchTermLimit,
            headers = SiteHeaders(userAgent = userAgent, referer = referer),
            credentialsRequired = credentialsRequired,
            addedAtEpochMs = addedAt ?: return null,
            verifiedAtEpochMs = verifiedAt,
            iconUrl = iconUrl?.takeIf { it.startsWith("https://") },
        )
    }.getOrNull()

    companion object {
        fun fromDomain(profile: SiteProfile): SiteProfileRecord = SiteProfileRecord(
            sourceId = profile.sourceId,
            baseUrl = profile.address.baseUrl,
            label = profile.label,
            family = profile.familyId,
            searchTermLimit = profile.searchTermLimit,
            userAgent = profile.headers.userAgent,
            referer = profile.headers.referer,
            iconUrl = profile.iconUrl,
            credentialsRequired = profile.credentialsRequired,
            addedAt = profile.addedAtEpochMs,
            verifiedAt = profile.verifiedAtEpochMs,
        )
    }
}
