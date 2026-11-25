package com.theoriacodex.app.customsites

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProbeResult
import kotlinx.coroutines.flow.StateFlow

interface CustomSitesController {
    val sites: StateFlow<List<CustomSiteSummary>>

    val workingSourceIds: StateFlow<Set<String>>

    val builtInSites: StateFlow<List<BuiltInSiteSummary>>

    suspend fun testBuiltIn(source: SourceKey): BuiltInTestResult

    suspend fun checkVerification(source: SourceKey, userAgent: String): Boolean

    suspend fun completeVerification(source: SourceKey, userAgent: String)

    suspend fun probe(
        input: String,
        credentials: SiteCredentials? = null,
        headers: SiteHeaders = SiteHeaders(),
    ): SiteProbeResult

    suspend fun add(draft: CustomSiteDraft): CustomSiteMutation

    suspend fun retest(sourceId: String): SiteProbeResult

    fun setSetupHost(host: String?)

    suspend fun update(sourceId: String, edit: CustomSiteEdit): CustomSiteMutation

    suspend fun remove(sourceId: String): CustomSiteMutation

    val setupRequest: StateFlow<SitesRequest?>

    fun requestSetup(address: String, linkAfterSetup: String? = null)

    fun requestKeyEntry(sourceId: String)

    fun clearSetupRequest()

    val linkAfterSetup: StateFlow<String?>

    fun clearLinkAfterSetup()
}

sealed interface SitesRequest {
    data class Add(val address: String, val linkAfterSetup: String? = null) : SitesRequest
    data class EnterKey(val sourceId: String) : SitesRequest
}

data class CustomSiteSummary(
    val sourceId: String,
    val label: String,
    val host: String,
    val baseUrl: String,
    val family: ProtocolFamily?,
    val availability: CustomSiteAvailability,
    val headers: SiteHeaders,
    val hasCredentials: Boolean,
    val includedInUnifiedAndForYou: Boolean,
    val verifiedAtEpochMs: Long?,
)

data class BuiltInSiteSummary(
    val source: SourceKey,
    val label: String,
    val host: String,
    val working: Boolean,
    val needsVerification: Boolean,
)

enum class BuiltInTestResult { WORKING, NEEDS_BROWSER_CHECK, NEEDS_ACCOUNT, DOWN, RATE_LIMITED, UNREACHABLE, FAILED }

enum class CustomSiteAvailability {
    AVAILABLE,

    NEEDS_CREDENTIALS,

    UNSUPPORTED_FAMILY,
}

data class CustomSiteDraft(
    val probe: SiteProbeResult.Supported,
    val label: String,
    val credentials: SiteCredentials? = null,
    val headers: SiteHeaders = SiteHeaders(),
    val includeInUnifiedAndForYou: Boolean = false,
    val confirmReconnect: Boolean = false,
    val linkAfterSetup: String? = null,
)

data class CustomSiteEdit(
    val label: String? = null,
    val credentials: CredentialEdit = CredentialEdit.Keep,
    val userAgent: HeaderEdit = HeaderEdit.Keep,
    val referer: HeaderEdit = HeaderEdit.Keep,
    val includeInUnifiedAndForYou: Boolean? = null,
)

sealed interface HeaderEdit {
    data object Keep : HeaderEdit
    data class Replace(val value: String?) : HeaderEdit

    fun applyTo(current: String?): String? = if (this is Replace) value else current
}

sealed interface CredentialEdit {
    data object Keep : CredentialEdit
    data object Clear : CredentialEdit
    data class Replace(val credentials: SiteCredentials) : CredentialEdit
}

sealed interface CustomSiteMutation {
    data object Success : CustomSiteMutation

    data class DuplicateHost(val existingSourceId: String) : CustomSiteMutation

    data class ReconnectConfirmationNeeded(val hostLabel: String) : CustomSiteMutation

    data object LimitReached : CustomSiteMutation
    data object NotFound : CustomSiteMutation
    data object CredentialStoreUnavailable : CustomSiteMutation
    data class InvalidLabel(val maxLength: Int) : CustomSiteMutation
}
