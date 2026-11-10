package com.theoriacodex.app.di

import com.theoriacodex.app.customsites.BuiltInAgentStore
import com.theoriacodex.app.customsites.BuiltInSiteSummary
import com.theoriacodex.app.customsites.BuiltInTestResult
import com.theoriacodex.app.customsites.CredentialEdit
import com.theoriacodex.app.customsites.CustomSiteDraft
import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteMutation
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.app.customsites.CustomSitesController
import com.theoriacodex.app.customsites.SiteCookieBridge
import com.theoriacodex.app.customsites.SitesRequest
import com.theoriacodex.app.sourceauth.CredentialStoreUnavailableException
import com.theoriacodex.app.source.clearSource
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.inPresentationOrder
import com.theoriacodex.app.source.ownsHost
import com.theoriacodex.app.source.presentation
import com.theoriacodex.app.source.sourceIssues
import com.theoriacodex.app.sourceauth.SiteCredentialsStore
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.SiteProfileAddResult
import com.theoriacodex.data.repository.SiteProfileRepository
import com.theoriacodex.data.repository.SourceLibraryPresence
import com.theoriacodex.data.repository.SourceWeightSettings
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.CustomSourceIds
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProbeResult
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.custom.SiteProber
import com.theoriacodex.sources.http.HostOutage
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostOutageTracker
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → Sites over the profile store, the encrypted key store, the prober and the runtime
 * (plan C9). The Unified/For You opt-in is the site's membership in `enabledSources` (plan D4).
 * Removing a site deletes its profile, keys and opt-in, never library data. Completing a browser check
 * ties the saved agent, the site's outage and the browser's cookies together in one step; checking it
 * changes nothing.
 */
internal class CustomSitesControllerImpl(
    private val profiles: SiteProfileRepository,
    private val credentials: SiteCredentialsStore,
    private val prober: SiteProber,
    runtime: StateFlow<CustomSourceSnapshot>,
    private val settings: SettingsRepository,
    private val library: SourceLibraryPresence,
    scope: CoroutineScope,
    private val outages: HostOutageTracker,
    private val cookieBridge: SiteCookieBridge,
    private val builtInAgents: BuiltInAgentStore,
    /** Whether a URL answers a request with this User-Agent without a challenge or an outage. */
    private val clearsChallenge: suspend (url: String, userAgent: String) -> Boolean,
    /** Runs one real search through a built-in source; false when it needs an account to search at all. */
    private val runBuiltInSearch: suspend (SourceKey) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : CustomSitesController {
    override val sites: StateFlow<List<CustomSiteSummary>> =
        combine(runtime, settings.observeSettings().map { it.runtime.enabledSources }.distinctUntilChanged()) { snapshot, enabled ->
            snapshot.sites.map { site -> site.toSummary(included = site.source in enabled) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val workingSourceIds: StateFlow<Set<String>> =
        combine(sites, outages.working) { stored, hosts ->
            stored.filter { HostOutageTracker.normalizeHost(it.host) in hosts }.map { it.sourceId }.toSet()
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    override val builtInSites: StateFlow<List<BuiltInSiteSummary>> =
        combine(outages.outages, outages.working) { recorded, working ->
            BUILT_IN_SOURCES.map { it.toBuiltInSummary(recorded, working) }
        }.stateIn(scope, SharingStarted.Eagerly, BUILT_IN_SOURCES.map { it.toBuiltInSummary(emptyMap(), emptySet()) })

    private fun SourceKey.toBuiltInSummary(recorded: Map<String, HostOutage>, working: Set<String>): BuiltInSiteSummary {
        val issue = sourceIssues(recorded, listOf(this), clock())[this]
        return BuiltInSiteSummary(
            source = this,
            label = displayName(),
            host = URI(presentation().referer).host,
            working = issue == null && working.any(::ownsHost),
            needsVerification = issue == HostOutageKind.CHALLENGE,
        )
    }

    private val pendingSetup = MutableStateFlow<SitesRequest?>(null)
    override val setupRequest: StateFlow<SitesRequest?> = pendingSetup.asStateFlow()

    override fun requestSetup(address: String) {
        pendingSetup.value = SitesRequest.Add(address)
    }

    override fun requestVerification(sourceId: String) {
        pendingSetup.value = SitesRequest.Verify(sourceId)
    }

    override fun clearSetupRequest() {
        pendingSetup.value = null
    }

    override suspend fun probe(input: String, credentials: SiteCredentials?, headers: SiteHeaders): SiteProbeResult =
        prober.probe(input, credentials, headers)

    override suspend fun add(draft: CustomSiteDraft): CustomSiteMutation {
        val label = validLabel(draft.label) ?: return CustomSiteMutation.InvalidLabel(SiteProfile.MAX_LABEL_LENGTH)
        val now = clock()
        val address = draft.probe.address
        val profile = SiteProfile(
            sourceId = address.derivedSourceId,
            address = address,
            label = label,
            familyId = draft.probe.family.name,
            searchTermLimit = draft.probe.searchTermLimit,
            headers = draft.headers,
            credentialsRequired = draft.probe.credentialsRequired,
            addedAtEpochMs = now,
            verifiedAtEpochMs = now,
            iconUrl = draft.probe.iconUrl,
        )
        val source = SourceKey.of(profile)
        if (find(profile.sourceId) != null) return CustomSiteMutation.DuplicateHost(profile.sourceId)
        // The same address derives the same id, so stored rows may belong to a removed site (plan D2).
        if (!draft.confirmReconnect && library.hasLibraryData(source)) {
            return CustomSiteMutation.ReconnectConfirmationNeeded(CustomSourceIds.hostLabel(profile.sourceId))
        }
        when (val added = profiles.add(profile)) {
            SiteProfileAddResult.Added -> Unit
            is SiteProfileAddResult.Duplicate -> return CustomSiteMutation.DuplicateHost(added.existingSourceId)
            SiteProfileAddResult.LimitReached -> return CustomSiteMutation.LimitReached
        }
        draft.credentials?.let { keys ->
            if (!writeKeys { credentials.save(profile.sourceId, keys) }) {
                profiles.remove(profile.sourceId)
                return CustomSiteMutation.CredentialStoreUnavailable
            }
        }
        if (draft.includeInUnifiedAndForYou) setIncluded(source, included = true)
        // The check that led here just got an answer, so a mark from before a removal no longer applies.
        outages.clear(address.host)
        return CustomSiteMutation.Success
    }

    override suspend fun retest(sourceId: String): SiteProbeResult {
        val profile = requireNotNull(find(sourceId)) { "Re-test needs a stored site" }
        val result = prober.probe(profile.address.baseUrl, credentials.get(sourceId), profile.headers, knownFamily = profile.family)
        // A different family is reported to the user, never adopted (remove and re-add to switch).
        val working = result is SiteProbeResult.Supported && result.family == profile.family
        if (working) {
            outages.clear(profile.address.host)
            // Re-testing also picks up an icon the site declared since, or one missed before.
            profiles.update(sourceId) { it.copy(verifiedAtEpochMs = clock(), iconUrl = result.iconUrl ?: it.iconUrl) }
        }
        outages.setWorking(profile.address.baseUrl, working)
        return result
    }

    override suspend fun checkVerification(sourceId: String, userAgent: String): SiteProbeResult {
        val profile = requireNotNull(find(sourceId)) { "Verification needs a stored site" }
        return prober.probe(
            profile.address.baseUrl,
            credentials.get(sourceId),
            profile.headers.copy(userAgent = userAgent),
            knownFamily = profile.family,
        )
    }

    override suspend fun completeVerification(sourceId: String, userAgent: String, working: Boolean) {
        val profile = requireNotNull(find(sourceId)) { "Verification needs a stored site" }
        profiles.update(sourceId) { it.copy(headers = it.headers.copy(userAgent = userAgent), verifiedAtEpochMs = clock()) }
        outages.clearSource(SourceKey.of(profile))
        outages.setWorking(profile.address.baseUrl, working)
        cookieBridge.flush()
    }

    override suspend fun testBuiltIn(source: SourceKey): BuiltInTestResult {
        outages.clearSource(source)
        val searched = runCatchingPreservingCancellation { withTimeoutOrNull(BUILT_IN_TEST_TIMEOUT_MS) { runBuiltInSearch(source) } }
        // The tracker goes first: a source with a fallback mirror can return posts while its own host is challenged.
        val result = when (sourceIssues(outages.outages.value, listOf(source), clock())[source]) {
            HostOutageKind.CHALLENGE -> BuiltInTestResult.NEEDS_BROWSER_CHECK
            HostOutageKind.MAINTENANCE -> BuiltInTestResult.DOWN
            HostOutageKind.UNRESOLVED -> BuiltInTestResult.UNREACHABLE
            null -> searched.fold(
                onSuccess = { finished ->
                    when (finished) {
                        true -> BuiltInTestResult.WORKING
                        false -> BuiltInTestResult.NEEDS_ACCOUNT
                        null -> BuiltInTestResult.UNREACHABLE
                    }
                },
                onFailure = ::builtInTestFailure,
            )
        }
        outages.setWorking(source.presentation().referer, result == BuiltInTestResult.WORKING)
        return result
    }

    override suspend fun checkBuiltInVerification(source: SourceKey, userAgent: String): Boolean {
        // An API host is often what challenged, and it can stay challenged after the home page clears.
        val outageHosts = outages.outages.value.keys.filter(source::ownsHost).map { "https://$it/" }
        return (listOf(source.presentation().referer) + outageHosts).all { clearsChallenge(it, userAgent) }
    }

    override suspend fun completeBuiltInVerification(source: SourceKey, userAgent: String) {
        val home = source.presentation().referer
        builtInAgents.save(requireNotNull(HostOutageTracker.hostKey(home)), userAgent)
        cookieBridge.updateBuiltInAgents(builtInAgents.read())
        outages.clearSource(source)
        outages.setWorking(home, true)
        cookieBridge.flush()
    }

    override fun setSetupHost(host: String?) = cookieBridge.setSetupHost(host)

    override suspend fun update(sourceId: String, edit: CustomSiteEdit): CustomSiteMutation {
        val profile = find(sourceId) ?: return CustomSiteMutation.NotFound
        val label = edit.label?.let { validLabel(it) ?: return CustomSiteMutation.InvalidLabel(SiteProfile.MAX_LABEL_LENGTH) }
        val keysWritten = when (val keys = edit.credentials) {
            CredentialEdit.Keep -> true
            CredentialEdit.Clear -> writeKeys { credentials.clear(sourceId) }
            is CredentialEdit.Replace -> writeKeys { credentials.save(sourceId, keys.credentials) }
        }
        if (!keysWritten) return CustomSiteMutation.CredentialStoreUnavailable
        // New keys may be what the site was waiting for, so requests should reach it again.
        if (edit.credentials != CredentialEdit.Keep) outages.clear(profile.address.host)
        if (label != null || edit.headers != null) {
            val updated = profiles.update(sourceId) { it.copy(label = label ?: it.label, headers = edit.headers ?: it.headers) }
            if (!updated) return CustomSiteMutation.NotFound
        }
        edit.includeInUnifiedAndForYou?.let { setIncluded(SourceKey.of(profile), it) }
        return CustomSiteMutation.Success
    }

    override suspend fun remove(sourceId: String): CustomSiteMutation {
        val profile = find(sourceId) ?: return CustomSiteMutation.NotFound
        // Keys first: a profile is never left removed while its keys survive.
        if (!writeKeys { credentials.clear(sourceId) }) return CustomSiteMutation.CredentialStoreUnavailable
        if (!profiles.remove(sourceId)) return CustomSiteMutation.NotFound
        setIncluded(SourceKey.of(profile), included = false)
        cookieBridge.forget(profile.address.host)
        outages.setWorking(profile.address.baseUrl, false)
        return CustomSiteMutation.Success
    }

    private suspend fun find(sourceId: String): SiteProfile? =
        profiles.observeProfiles().first().firstOrNull { it.sourceId == sourceId }

    private suspend fun setIncluded(source: SourceKey, included: Boolean) {
        settings.updateSettings { current ->
            val enabled = current.runtime.enabledSources
            val next = if (included) enabled + source else enabled - source
            if (next == enabled) current else current.copy(runtime = SourceWeightSettings.selectSources(current.runtime, next))
        }
    }

    /** False when the encrypted store cannot be written right now. */
    private suspend fun writeKeys(write: suspend () -> Unit): Boolean = try {
        write()
        true
    } catch (_: CredentialStoreUnavailableException) {
        false
    }

    private fun validLabel(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= SiteProfile.MAX_LABEL_LENGTH }
}

private val BUILT_IN_SOURCES = SourceKey.builtIns.inPresentationOrder()

/** Long enough for a slow first search, short enough that one stuck source doesn't stall Test all. */
private const val BUILT_IN_TEST_TIMEOUT_MS = 20_000L

private fun builtInTestFailure(error: Throwable): BuiltInTestResult = when ((error as? SourceAdapterException)?.reason) {
    SourceFailureReason.AUTH_REQUIRED, SourceFailureReason.AUTH_EXPIRED -> BuiltInTestResult.NEEDS_ACCOUNT
    SourceFailureReason.NETWORK -> BuiltInTestResult.UNREACHABLE
    else -> if (error is IOException) BuiltInTestResult.UNREACHABLE else BuiltInTestResult.FAILED
}

private fun CustomSiteState.toSummary(included: Boolean) = CustomSiteSummary(
    sourceId = profile.sourceId,
    label = profile.label,
    host = profile.address.host,
    baseUrl = profile.address.baseUrl,
    family = profile.family,
    availability = availability,
    headers = profile.headers,
    hasCredentials = hasCredentials,
    includedInUnifiedAndForYou = included,
    verifiedAtEpochMs = profile.verifiedAtEpochMs,
)
