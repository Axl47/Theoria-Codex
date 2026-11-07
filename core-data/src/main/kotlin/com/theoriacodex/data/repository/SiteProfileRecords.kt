package com.theoriacodex.data.repository

import com.google.gson.annotations.SerializedName
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProfile

/**
 * Durable JSON for one user-added site, shared by the site-profile store (`custom_sites_v1.json`)
 * and backups (`customSources`). Never holds credentials. Every field has a default so Gson can
 * construct it; validation happens in [toDomainOrNull]. Listed in the R8 JSON contract manifest.
 */
internal data class SiteProfileRecord(
    @field:SerializedName("sourceId") val sourceId: String? = null,
    @field:SerializedName("baseUrl") val baseUrl: String? = null,
    @field:SerializedName("label") val label: String? = null,
    @field:SerializedName("family") val family: String? = null,
    @field:SerializedName("searchTermLimit") val searchTermLimit: Int? = null,
    @field:SerializedName("userAgent") val userAgent: String? = null,
    @field:SerializedName("referer") val referer: String? = null,
    @field:SerializedName("credentialsRequired") val credentialsRequired: Boolean = false,
    @field:SerializedName("addedAt") val addedAt: Long? = null,
    @field:SerializedName("verifiedAt") val verifiedAt: Long? = null,
    @field:SerializedName("iconUrl") val iconUrl: String? = null,
) {
    /**
     * Null for an invalid record. The local store skips such records so one bad entry cannot lock
     * the user out; the backup decoder treats null as an invalid backup.
     */
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
