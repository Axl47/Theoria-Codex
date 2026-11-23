package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.app.customsites.BuiltInSiteSummary
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteProbeResult

/** Settings → Sites: built-in and added sites, plus whichever page, form, dialog or inline result is open. */
data class CustomSitesUiState(
    val sites: List<CustomSiteSummary> = emptyList(),
    val builtInSites: List<BuiltInSiteSummary> = emptyList(),
    /** The built-in source whose page is open, if any. */
    val openBuiltIn: SourceKey? = null,
    val form: SiteForm? = null,
    /** Inline re-test status per source id; cleared when the site disappears. */
    val retests: Map<String, RetestStatus> = emptyMap(),
    /** Source ids proven to work this session, which their rows show with a check mark. */
    val workingSiteIds: Set<String> = emptySet(),
    /** The Test all run in progress, if any. */
    val testProgress: SitesTestProgress? = null,
    /** The last finished Test all run's result line. */
    val testSummary: String? = null,
    /** Sites removed but held back for Undo (or Retry), by source id; each row draws its Undo instead. */
    val removals: Map<String, PendingRemoval> = emptyMap(),
    /** A short failure message shown above the list until the next action. */
    val notice: String? = null,
) {
    /** The added sites as the list shows them, without those waiting to be removed. */
    val shownSites: List<CustomSiteSummary> get() = sites.filterNot { it.sourceId in removals }
}

/** Test all is on the [position]th of [total] sites, named [label]. */
data class SitesTestProgress(val label: String, val position: Int, val total: Int)

/** Text fields shared by the add and edit forms. Blank header fields mean "use the default". */
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
        /** True once the probe asked for keys or the user chose to enter them. */
        val showCredentials: Boolean = false,
        val stage: AddStage = AddStage.Address(),
        /** The link that led here, which opens once this site is saved. */
        val linkAfterSetup: String? = null,
    ) : SiteForm

    data class Edit(
        val sourceId: String,
        override val fields: SiteFields,
        val hasCredentials: Boolean,
        /** Opens the API key section, for someone sent here because the site refused their access. */
        val expandKeys: Boolean = false,
        /** Saved keys will be cleared on save; new key fields are ignored while set. */
        val clearCredentials: Boolean = false,
        val saving: Boolean = false,
        val message: String? = null,
    ) : SiteForm
}

/** Where the add flow is. Editing the address or headers returns it to [Address]. */
sealed interface AddStage {
    /** Entering the address; [message] explains the last probe result that can't be added. */
    data class Address(val message: String? = null, val blockedHost: String? = null) : AddStage

    data object Probing : AddStage

    /** The site answered as [family] but wants keys; [rejected] when the entered keys failed. */
    data class Credentials(val family: ProtocolFamily, val rejected: Boolean) : AddStage

    /** Ready to save; [message] explains a failed save (label, duplicate, limit, key storage). */
    data class Confirm(val probe: SiteProbeResult.Supported, val message: String? = null) : AddStage

    data class Saving(val probe: SiteProbeResult.Supported) : AddStage

    /** The site's id already has library data here; the user decides whether to reuse it. */
    data class Reconnect(val probe: SiteProbeResult.Supported, val hostLabel: String) : AddStage
}

sealed interface RetestStatus {
    data object Running : RetestStatus

    /**
     * [blocked] when a browser challenge refused the check, so the row offers browser verification;
     * [needsKey] when the site asked for keys or refused the saved ones, so it offers the key field.
     */
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
    /** Removes a site with Undo in its row; the removal commits after the Undo times out or Sites is left. */
    data class Remove(val sourceId: String) : CustomSitesAction

    /** A removed site row's Undo, or its Retry after the removal failed. */
    data class ActOnRemoval(val sourceId: String) : CustomSitesAction

    /** Commits every removal still held for Undo; sent when Sites is left. */
    data object CommitRemovals : CustomSitesAction
    data object DismissNotice : CustomSitesAction

    /** Opens the app's browser check for [host]: a built-in, stored or Add form site. */
    data class StartVerification(val host: String) : CustomSitesAction
}
