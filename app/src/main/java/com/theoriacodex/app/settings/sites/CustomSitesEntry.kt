package com.theoriacodex.app.settings.sites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.customsites.CustomSiteDraft
import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteMutation
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.app.customsites.CustomSitesController
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.settings.SettingsSitesEntry
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressError
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProbeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Builds the Settings home row and page for Sites from the activity-retained [CustomSitesViewModel]. */
@Composable
internal fun rememberCustomSitesEntry(container: TheoriaAppContainer): SettingsSitesEntry {
    val owner = viewModel<CustomSitesViewModel>(key = "custom-sites", factory = CustomSitesViewModel.factory(container))
    val state by owner.state.collectAsStateWithLifecycle()
    return SettingsSitesEntry(summary = customSitesSummary(state.sites)) { onBack ->
        CustomSitesScreen(state, owner::onAction, onBack)
    }
}

// STEP 3 SEAM: delete this property and PendingCustomSitesController once the container exposes the
// real CustomSitesControllerImpl (plan C9). A member named `customSites` on TheoriaAppContainer
// shadows this extension, so CustomSitesViewModel.factory needs no change if it lands there.
internal val TheoriaAppContainer.customSites: CustomSitesController
    get() = PendingCustomSitesController

/**
 * Placeholder until step 3: lists no sites, validates addresses for real, and reports every valid
 * address as unreachable, because nothing can probe or store a site yet.
 */
private object PendingCustomSitesController : CustomSitesController {
    private val noSites = MutableStateFlow(emptyList<CustomSiteSummary>())
    override val sites: StateFlow<List<CustomSiteSummary>> = noSites.asStateFlow()

    override suspend fun probe(
        input: String,
        credentials: SiteCredentials?,
        headers: SiteHeaders,
    ): SiteProbeResult = pendingProbe(input)

    override suspend fun add(draft: CustomSiteDraft) = CustomSiteMutation.NotFound

    override suspend fun retest(sourceId: String): SiteProbeResult = SiteProbeResult.InvalidAddress(SiteAddressError.MALFORMED)

    override suspend fun update(sourceId: String, edit: CustomSiteEdit) =
        CustomSiteMutation.NotFound

    override suspend fun remove(sourceId: String) = CustomSiteMutation.NotFound

    private fun pendingProbe(input: String): SiteProbeResult = when (val parsed = SiteAddress.parse(input)) {
        is SiteAddressParse.Invalid -> SiteProbeResult.InvalidAddress(parsed.error)
        is SiteAddressParse.Valid -> SiteProbeResult.Unreachable(parsed.address, ProbeFailure.HTTP_ERROR)
    }
}
