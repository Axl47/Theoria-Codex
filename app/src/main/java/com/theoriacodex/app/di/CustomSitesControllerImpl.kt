package com.theoriacodex.app.di

import com.theoriacodex.app.customsites.CredentialEdit
import com.theoriacodex.app.customsites.CustomSiteDraft
import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteMutation
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.app.customsites.CustomSitesController
import com.theoriacodex.app.sourceauth.CredentialStoreUnavailableException
import com.theoriacodex.app.sourceauth.SiteCredentialsStore
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.SiteProfileAddResult
import com.theoriacodex.data.repository.SiteProfileRepository
import com.theoriacodex.data.repository.SourceLibraryPresence
import com.theoriacodex.data.repository.SourceWeightSettings
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.CustomSourceIds
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProbeResult
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.custom.SiteProber
import kotlinx.coroutines.CoroutineScope
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
 * Removing a site deletes its profile, keys and opt-in, never library data.
 */
internal class CustomSitesControllerImpl(
    private val profiles: SiteProfileRepository,
    private val credentials: SiteCredentialsStore,
    private val prober: SiteProber,
    runtime: StateFlow<CustomSourceSnapshot>,
    private val settings: SettingsRepository,
    private val library: SourceLibraryPresence,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : CustomSitesController {
    override val sites: StateFlow<List<CustomSiteSummary>> =
        combine(runtime, settings.observeSettings().map { it.runtime.enabledSources }.distinctUntilChanged()) { snapshot, enabled ->
            snapshot.sites.map { site -> site.toSummary(included = site.source in enabled) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val pendingSetup = MutableStateFlow<String?>(null)
    override val setupRequest: StateFlow<String?> = pendingSetup.asStateFlow()

    override fun requestSetup(address: String) {
        pendingSetup.value = address
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
        return CustomSiteMutation.Success
    }

    override suspend fun retest(sourceId: String): SiteProbeResult {
        val profile = requireNotNull(find(sourceId)) { "Re-test needs a stored site" }
        val result = prober.probe(profile.address.baseUrl, credentials.get(sourceId), profile.headers)
        // A different family is reported to the user, never adopted (remove and re-add to switch).
        if (result is SiteProbeResult.Supported && result.family == profile.family) {
            profiles.update(sourceId) { it.copy(verifiedAtEpochMs = clock()) }
        }
        return result
    }

    override suspend fun update(sourceId: String, edit: CustomSiteEdit): CustomSiteMutation {
        val profile = find(sourceId) ?: return CustomSiteMutation.NotFound
        val label = edit.label?.let { validLabel(it) ?: return CustomSiteMutation.InvalidLabel(SiteProfile.MAX_LABEL_LENGTH) }
        val keysWritten = when (val keys = edit.credentials) {
            CredentialEdit.Keep -> true
            CredentialEdit.Clear -> writeKeys { credentials.clear(sourceId) }
            is CredentialEdit.Replace -> writeKeys { credentials.save(sourceId, keys.credentials) }
        }
        if (!keysWritten) return CustomSiteMutation.CredentialStoreUnavailable
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
