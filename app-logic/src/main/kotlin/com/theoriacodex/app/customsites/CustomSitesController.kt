package com.theoriacodex.app.customsites

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProbeResult
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel-facing API for Settings → Sites (plan contract C9). Step 3 implements it in the app's
 * DI layer; step 4's ViewModel consumes it. Removing a site never deletes library data.
 */
interface CustomSitesController {
    /** Every stored site in the order it was added, with live availability. */
    val sites: StateFlow<List<CustomSiteSummary>>

    /** Stored sites proven to work since the app started: a real API answer, a passed re-test or browser check. */
    val workingSourceIds: StateFlow<Set<String>>

    /** The sources that ship with the app, in presentation order, with their live status. */
    val builtInSites: StateFlow<List<BuiltInSiteSummary>>

    /**
     * Runs one real search through [source], retrying it even if it is in an outage, and records whether
     * it works this session. The posts it finds are discarded.
     */
    suspend fun testBuiltIn(source: SourceKey): BuiltInTestResult

    /**
     * Whether [source]'s home page and any of its hosts now in an outage answer with [userAgent], the
     * in-app browser's, and without a challenge. Changes nothing.
     */
    suspend fun checkBuiltInVerification(source: SourceKey, userAgent: String): Boolean

    /**
     * Records a passed [checkBuiltInVerification]: [source]'s requests send the browser's cookies and
     * [userAgent] from now on, and its outage lifts so it works at once. Makes no request.
     */
    suspend fun completeBuiltInVerification(source: SourceKey, userAgent: String)

    /** Add, step 1: validate the address and probe known families. Persists nothing. */
    suspend fun probe(
        input: String,
        credentials: SiteCredentials? = null,
        headers: SiteHeaders = SiteHeaders(),
    ): SiteProbeResult

    /** Add, step 2: save a confirmed [SiteProbeResult.Supported]. */
    suspend fun add(draft: CustomSiteDraft): CustomSiteMutation

    /**
     * Re-probes a stored site's address. A same-family success refreshes its verified time; a
     * different family is reported, never switched to. [sourceId] must be stored.
     */
    suspend fun retest(sourceId: String): SiteProbeResult

    /**
     * Whether the app's own request gets through to a stored site with [userAgent], the in-app browser's,
     * which its clearance cookies are usually bound to. Probes with the stored keys and the site's other
     * headers and changes nothing. [sourceId] must be stored.
     */
    suspend fun checkVerification(sourceId: String, userAgent: String): SiteProbeResult

    /**
     * Records a passed [checkVerification]: saves [userAgent] (keeping the site's other headers), stamps
     * the verified time, lifts the site's challenge outage and flushes the browser's cookies, so the
     * source works at once. [working] is whether the check also found the expected booru rather than
     * merely getting past the challenge. Makes no request. [sourceId] must be stored.
     */
    suspend fun completeVerification(sourceId: String, userAgent: String, working: Boolean)

    /**
     * Lets the browser's cookies reach requests to [host] while it is verified before being saved, or
     * stops that when null. Saved sites always get their cookies.
     */
    fun setSetupHost(host: String?)

    suspend fun update(sourceId: String, edit: CustomSiteEdit): CustomSiteMutation

    /** Removes the profile, its credentials and its Unified/For You opt-in; library data stays. */
    suspend fun remove(sourceId: String): CustomSiteMutation

    /**
     * What another flow asked Settings → Sites to do: add an address (a Codex import, a shared link),
     * verify a stored site in the browser (a challenged source) or open its API key field (a refused
     * account). Sites acts on it and calls [clearSetupRequest].
     */
    val setupRequest: StateFlow<SitesRequest?>

    /** [linkAfterSetup] is opened once the site is saved; abandoning the form discards it. */
    fun requestSetup(address: String, linkAfterSetup: String? = null)

    fun requestVerification(sourceId: String)

    fun requestKeyEntry(sourceId: String)

    fun clearSetupRequest()

    /** A link waiting on the site just saved for it, ready to open. The app opens it and calls [clearLinkAfterSetup]. */
    val linkAfterSetup: StateFlow<String?>

    fun clearLinkAfterSetup()
}

sealed interface SitesRequest {
    data class Add(val address: String, val linkAfterSetup: String? = null) : SitesRequest
    data class Verify(val sourceId: String) : SitesRequest
    data class EnterKey(val sourceId: String) : SitesRequest
}

data class CustomSiteSummary(
    val sourceId: String,
    val label: String,
    val host: String,
    val baseUrl: String,
    /** Null when this app version doesn't know the stored family. */
    val family: ProtocolFamily?,
    val availability: CustomSiteAvailability,
    val headers: SiteHeaders,
    val hasCredentials: Boolean,
    val includedInUnifiedAndForYou: Boolean,
    val verifiedAtEpochMs: Long?,
)

/** A source that ships with the app. [host] is the address its browser check opens. */
data class BuiltInSiteSummary(
    val source: SourceKey,
    val label: String,
    val host: String,
    val working: Boolean,
    val needsVerification: Boolean,
)

/** What a real search through a built-in source found. */
enum class BuiltInTestResult { WORKING, NEEDS_BROWSER_CHECK, NEEDS_ACCOUNT, DOWN, UNREACHABLE, FAILED }

enum class CustomSiteAvailability {
    AVAILABLE,

    /** The site refuses anonymous access and no keys are stored (for example after a restore). */
    NEEDS_CREDENTIALS,

    /** Stored by a newer app version with a family this one cannot use. */
    UNSUPPORTED_FAMILY,
}

/**
 * A confirmed site to save. Whether keys are required comes from [probe]; [headers] are the ones
 * the probe used. Set [confirmReconnect] after the user answers [CustomSiteMutation.ReconnectConfirmationNeeded].
 */
data class CustomSiteDraft(
    val probe: SiteProbeResult.Supported,
    val label: String,
    val credentials: SiteCredentials? = null,
    val headers: SiteHeaders = SiteHeaders(),
    val includeInUnifiedAndForYou: Boolean = false,
    val confirmReconnect: Boolean = false,
    val linkAfterSetup: String? = null,
)

/** Null fields keep their current value. */
data class CustomSiteEdit(
    val label: String? = null,
    val credentials: CredentialEdit = CredentialEdit.Keep,
    val headers: SiteHeaders? = null,
    val includeInUnifiedAndForYou: Boolean? = null,
)

sealed interface CredentialEdit {
    data object Keep : CredentialEdit
    data object Clear : CredentialEdit
    data class Replace(val credentials: SiteCredentials) : CredentialEdit
}

sealed interface CustomSiteMutation {
    data object Success : CustomSiteMutation

    /** An active profile already uses this site's id. */
    data class DuplicateHost(val existingSourceId: String) : CustomSiteMutation

    /** The id already has library data here (a removed site or restored rows); ask, then retry. */
    data class ReconnectConfirmationNeeded(val hostLabel: String) : CustomSiteMutation

    data object LimitReached : CustomSiteMutation
    data object NotFound : CustomSiteMutation
    data object CredentialStoreUnavailable : CustomSiteMutation
    data class InvalidLabel(val maxLength: Int) : CustomSiteMutation
}
