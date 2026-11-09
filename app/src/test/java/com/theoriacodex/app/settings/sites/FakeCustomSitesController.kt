package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.customsites.CustomSiteAvailability
import com.theoriacodex.app.customsites.CustomSiteDraft
import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteMutation
import com.theoriacodex.app.customsites.SitesRequest
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.app.customsites.CustomSitesController
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProbeResult
import kotlinx.coroutines.flow.MutableStateFlow

/** Scriptable [CustomSitesController] that records every call; successful adds and removals update [sites]. */
internal class FakeCustomSitesController(initial: List<CustomSiteSummary> = emptyList()) : CustomSitesController {
    data class ProbeCall(val input: String, val credentials: SiteCredentials?, val headers: SiteHeaders)

    override val sites: MutableStateFlow<List<CustomSiteSummary>> = MutableStateFlow(initial)
    override val setupRequest = MutableStateFlow<SitesRequest?>(null)

    override fun requestSetup(address: String) {
        setupRequest.value = SitesRequest.Add(address)
    }

    override fun requestVerification(sourceId: String) {
        setupRequest.value = SitesRequest.Verify(sourceId)
    }

    override fun clearSetupRequest() {
        setupRequest.value = null
    }
    var onProbe: suspend (ProbeCall) -> SiteProbeResult = { error("No probe result scripted") }
    var onAdd: suspend (CustomSiteDraft) -> CustomSiteMutation = { CustomSiteMutation.Success }
    var onRetest: suspend (String) -> SiteProbeResult = { error("No re-test result scripted") }
    var onUpdate: suspend (String, CustomSiteEdit) -> CustomSiteMutation = { _, _ -> CustomSiteMutation.Success }
    var onRemove: suspend (String) -> CustomSiteMutation = { CustomSiteMutation.Success }
    var onCheckVerification: suspend (String, String) -> SiteProbeResult = { _, _ -> error("No verification check scripted") }

    val probes = mutableListOf<ProbeCall>()
    val drafts = mutableListOf<CustomSiteDraft>()
    val edits = mutableListOf<Pair<String, CustomSiteEdit>>()
    val removals = mutableListOf<String>()

    /** Source id and agent of every [checkVerification] and [completeVerification] call. */
    val checks = mutableListOf<Pair<String, String>>()
    val completions = mutableListOf<Pair<String, String>>()

    /** Every [setSetupHost] value, in order. */
    val setupHosts = mutableListOf<String?>()

    override suspend fun probe(input: String, credentials: SiteCredentials?, headers: SiteHeaders): SiteProbeResult =
        ProbeCall(input, credentials, headers).also(probes::add).let { onProbe(it) }

    override suspend fun add(draft: CustomSiteDraft): CustomSiteMutation {
        drafts += draft
        return onAdd(draft).also { result ->
            if (result == CustomSiteMutation.Success) {
                val address = draft.probe.address
                sites.value += site(address.derivedSourceId, draft.label, address.host, draft.probe.family)
            }
        }
    }

    override suspend fun retest(sourceId: String): SiteProbeResult = onRetest(sourceId)

    override suspend fun checkVerification(sourceId: String, userAgent: String): SiteProbeResult {
        checks += sourceId to userAgent
        return onCheckVerification(sourceId, userAgent)
    }

    override suspend fun completeVerification(sourceId: String, userAgent: String) {
        completions += sourceId to userAgent
    }

    override fun setSetupHost(host: String?) {
        setupHosts += host
    }

    override suspend fun update(sourceId: String, edit: CustomSiteEdit): CustomSiteMutation {
        edits += sourceId to edit
        return onUpdate(sourceId, edit)
    }

    override suspend fun remove(sourceId: String): CustomSiteMutation {
        removals += sourceId
        return onRemove(sourceId).also { result ->
            if (result == CustomSiteMutation.Success) sites.value = sites.value.filterNot { it.sourceId == sourceId }
        }
    }
}

internal fun address(input: String): SiteAddress = (SiteAddress.parse(input) as SiteAddressParse.Valid).address

internal fun site(
    sourceId: String = "custom_safebooru_org",
    label: String = "Safebooru",
    host: String = "safebooru.org",
    family: ProtocolFamily? = ProtocolFamily.GELBOORU_02,
    availability: CustomSiteAvailability = CustomSiteAvailability.AVAILABLE,
    headers: SiteHeaders = SiteHeaders(),
    hasCredentials: Boolean = false,
    included: Boolean = false,
) = CustomSiteSummary(
    sourceId = sourceId,
    label = label,
    host = host,
    baseUrl = "https://$host",
    family = family,
    availability = availability,
    headers = headers,
    hasCredentials = hasCredentials,
    includedInUnifiedAndForYou = included,
    verifiedAtEpochMs = null,
)

internal fun supported(
    input: String = "safebooru.org",
    family: ProtocolFamily = ProtocolFamily.GELBOORU_02,
    label: String = "Safebooru",
    credentialsRequired: Boolean = false,
) = SiteProbeResult.Supported(address(input), family, label, searchTermLimit = null, credentialsRequired = credentialsRequired)
