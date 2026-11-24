package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.app.customsites.BuiltInSiteSummary
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteProbeResult

data class CustomSitesUiState(
    val sites: List<CustomSiteSummary> = emptyList(),
    val builtInSites: List<BuiltInSiteSummary> = emptyList(),
    val openBuiltIn: SourceKey? = null,
    val form: SiteForm? = null,
    val retests: Map<String, RetestStatus> = emptyMap(),
    val workingSiteIds: Set<String> = emptySet(),
    val testProgress: SitesTestProgress? = null,
    val testSummary: String? = null,
    val removals: Map<String, PendingRemoval> = emptyMap(),
    val notice: String? = null,
) {
    val shownSites: List<CustomSiteSummary> get() = sites.filterNot { it.sourceId in removals }
}

data class SitesTestProgress(val label: String, val position: Int, val total: Int)

data class SiteFields(
    val label: String = "",
    val user: String = "",
    val apiKey: String = "",
    val userAgent: String = "",
    val referer: String = "",
    val includeInUnifiedAndForYou: Boolean = false,
)

sealed interface SiteForm {
    val fields: SiteFields

    data class Add(
        val address: String = "",
        override val fields: SiteFields = SiteFields(),
        val showCredentials: Boolean = false,
        val stage: AddStage = AddStage.Address(),
        val linkAfterSetup: String? = null,
    ) : SiteForm

    data class Edit(
        val sourceId: String,
        override val fields: SiteFields,
        val hasCredentials: Boolean,
        val expandKeys: Boolean = false,
        val clearCredentials: Boolean = false,
        val saving: Boolean = false,
        val message: String? = null,
    ) : SiteForm
}

sealed interface AddStage {
    data class Address(val message: String? = null, val blockedHost: String? = null) : AddStage

    data object Probing : AddStage

    data class Credentials(val family: ProtocolFamily, val rejected: Boolean) : AddStage

    data class Confirm(val probe: SiteProbeResult.Supported, val message: String? = null) : AddStage

    data class Saving(val probe: SiteProbeResult.Supported) : AddStage

    data class Reconnect(val probe: SiteProbeResult.Supported, val hostLabel: String) : AddStage
}

sealed interface RetestStatus {
    data object Running : RetestStatus

    data class Done(val message: String, val ok: Boolean, val blocked: Boolean = false, val needsKey: Boolean = false) : RetestStatus
}

sealed interface CustomSitesAction {
    data object StartAdd : CustomSitesAction
    data class StartEdit(val sourceId: String, val expandKeys: Boolean = false) : CustomSitesAction
    data object CloseForm : CustomSitesAction
    data class OpenBuiltIn(val source: SourceKey) : CustomSitesAction
    data object CloseBuiltIn : CustomSitesAction

    data class SetAddress(val value: String) : CustomSitesAction
    data class SetLabel(val value: String) : CustomSitesAction
    data class SetUser(val value: String) : CustomSitesAction
    data class SetApiKey(val value: String) : CustomSitesAction
    data class SetUserAgent(val value: String) : CustomSitesAction
    data class SetReferer(val value: String) : CustomSitesAction
    data class SetIncludeInUnifiedAndForYou(val value: Boolean) : CustomSitesAction
    data object ShowCredentials : CustomSitesAction
    data class SetClearCredentials(val value: Boolean) : CustomSitesAction

    data object Probe : CustomSitesAction
    data object SaveNew : CustomSitesAction
    data object ConfirmReconnect : CustomSitesAction
    data object DismissReconnect : CustomSitesAction
    data object SaveEdit : CustomSitesAction

    data class Retest(val sourceId: String) : CustomSitesAction
    data object TestAll : CustomSitesAction
    data class Remove(val sourceId: String) : CustomSitesAction

    data class ActOnRemoval(val sourceId: String) : CustomSitesAction

    data object CommitRemovals : CustomSitesAction
    data object DismissNotice : CustomSitesAction

    data class StartVerification(val host: String) : CustomSitesAction
}
