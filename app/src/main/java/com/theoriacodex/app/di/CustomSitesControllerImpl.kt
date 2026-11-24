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
import com.theoriacodex.app.customsites.HeaderEdit
import com.theoriacodex.app.customsites.SiteCookieBridge
import com.theoriacodex.app.customsites.SitesRequest
import com.theoriacodex.app.sourceauth.CredentialStoreUnavailableException
import com.theoriacodex.app.source.clearSource
import com.theoriacodex.app.source.passSource
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
import com.theoriacodex.domain.source.clearedChallenge
import com.theoriacodex.sources.custom.SiteProber
import com.theoriacodex.sources.http.HostOutage
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostOutageTracker
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
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
    private val clearsChallenge: suspend (url: String, userAgent: String) -> Boolean,
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

    private val lastChecks = ConcurrentHashMap<String, SiteProbeResult>()

    private val pendingSetup = MutableStateFlow<SitesRequest?>(null)
    override val setupRequest: StateFlow<SitesRequest?> = pendingSetup.asStateFlow()

    override fun requestSetup(address: String, linkAfterSetup: String?) {
        pendingSetup.value = SitesRequest.Add(address, linkAfterSetup)
    }

    override fun requestKeyEntry(sourceId: String) {
        pendingSetup.value = SitesRequest.EnterKey(sourceId)
    }

    override fun clearSetupRequest() {
        pendingSetup.value = null
    }

    private val pendingLink = MutableStateFlow<String?>(null)
    override val linkAfterSetup: StateFlow<String?> = pendingLink.asStateFlow()

    override fun clearLinkAfterSetup() {
        pendingLink.value = null
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
        outages.clear(address.host)
        draft.linkAfterSetup?.let { link ->
            withTimeoutOrNull(LIVE_SOURCE_WAIT_MS) {
                sites.first { live -> live.any { it.sourceId == profile.sourceId && it.hasCredentials == (draft.credentials != null) } }
            }
            pendingLink.value = link
        }
        return CustomSiteMutation.Success
    }

    override suspend fun retest(sourceId: String): SiteProbeResult {
        val profile = requireNotNull(find(sourceId)) { "Re-test needs a stored site" }
        val result = prober.probe(profile.address.baseUrl, credentials.get(sourceId), profile.headers, knownFamily = profile.family)
        val working = result is SiteProbeResult.Supported && result.family == profile.family
        if (working) {
            outages.pass(profile.address.host)
            profiles.update(sourceId) { it.copy(verifiedAtEpochMs = clock(), iconUrl = result.iconUrl ?: it.iconUrl) }
        }
        outages.setWorking(profile.address.baseUrl, working)
        return result
    }


    override suspend fun testBuiltIn(source: SourceKey): BuiltInTestResult {
        outages.clearSource(source)
        val searched = runCatchingPreservingCancellation { withTimeoutOrNull(BUILT_IN_TEST_TIMEOUT_MS) { runBuiltInSearch(source) } }
        val result = when (sourceIssues(outages.outages.value, listOf(source), clock())[source]) {
            HostOutageKind.CHALLENGE -> BuiltInTestResult.NEEDS_BROWSER_CHECK
            HostOutageKind.MAINTENANCE -> BuiltInTestResult.DOWN
            HostOutageKind.RATE_LIMITED -> BuiltInTestResult.RATE_LIMITED
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

    override suspend fun checkVerification(source: SourceKey, userAgent: String): Boolean {
        if (!source.isCustom) {
            val outageHosts = outages.outages.value.keys.filter(source::ownsHost).map { "https://$it/" }
            return (listOf(source.presentation().referer) + outageHosts).all { clearsChallenge(it, userAgent) }
        }
        val profile = requireNotNull(find(source.id)) { "Verification needs a stored site" }
        val result = prober.probe(
            profile.address.baseUrl,
            credentials.get(source.id),
            profile.headers.copy(userAgent = userAgent),
            knownFamily = profile.family,
        )
        lastChecks[source.id] = result
        return result.clearedChallenge
    }

    override suspend fun completeVerification(source: SourceKey, userAgent: String) {
        if (source.isCustom) {
            val profile = requireNotNull(find(source.id)) { "Verification needs a stored site" }
            profiles.update(source.id) { it.copy(headers = it.headers.copy(userAgent = userAgent), verifiedAtEpochMs = clock()) }
            val checked = lastChecks.remove(source.id)
            outages.setWorking(profile.address.baseUrl, checked is SiteProbeResult.Supported && checked.family == profile.family)
        } else {
            val home = source.presentation().referer
            builtInAgents.save(requireNotNull(HostOutageTracker.hostKey(home)), userAgent)
            cookieBridge.updateBuiltInAgents(builtInAgents.read())
            outages.setWorking(home, true)
        }
        outages.passSource(source)
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
        if (edit.credentials != CredentialEdit.Keep) outages.clear(profile.address.host)
        if (label != null || edit.userAgent != HeaderEdit.Keep || edit.referer != HeaderEdit.Keep) {
            val updated = profiles.update(sourceId) { stored ->
                val headers = SiteHeaders(edit.userAgent.applyTo(stored.headers.userAgent), edit.referer.applyTo(stored.headers.referer))
                stored.copy(label = label ?: stored.label, headers = headers)
            }
            if (!updated) return CustomSiteMutation.NotFound
        }
        edit.includeInUnifiedAndForYou?.let { setIncluded(SourceKey.of(profile), it) }
        return CustomSiteMutation.Success
    }

    override suspend fun remove(sourceId: String): CustomSiteMutation {
        val profile = find(sourceId) ?: return CustomSiteMutation.NotFound
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

    private suspend fun writeKeys(write: suspend () -> Unit): Boolean = try {
        write()
        true
    } catch (_: CredentialStoreUnavailableException) {
        false
    }

    private fun validLabel(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= SiteProfile.MAX_LABEL_LENGTH }
}

private val BUILT_IN_SOURCES = SourceKey.builtIns.inPresentationOrder()

private const val BUILT_IN_TEST_TIMEOUT_MS = 20_000L
private const val LIVE_SOURCE_WAIT_MS = 5_000L

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
