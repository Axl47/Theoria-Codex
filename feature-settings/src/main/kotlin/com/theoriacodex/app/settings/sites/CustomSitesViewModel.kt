package com.theoriacodex.app.settings.sites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.customsites.BuiltInSiteSummary
import com.theoriacodex.app.customsites.CredentialEdit
import com.theoriacodex.app.customsites.CustomSiteDraft
import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteMutation
import com.theoriacodex.app.customsites.CustomSitesController
import com.theoriacodex.app.customsites.SitesRequest
import com.theoriacodex.app.source.SiteCheckTarget
import com.theoriacodex.app.source.SiteChecks
import com.theoriacodex.app.sourceauth.parseCredentialSnippet
import com.theoriacodex.app.ui.undo.DeferredRemovals
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProbeResult
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.domain.source.clearedChallenge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Owner of Settings → Sites (plan C9). Turns [CustomSitesController] results into form stages and
 * plain messages; it never persists anything itself. Removing a site never touches library data.
 * A removal is held back for Undo ([DeferredRemovals]) and commits in [removalScope], which must outlive this
 * owner so leaving Settings, or clearing this owner, still completes it. Edit page changes are written there too,
 * one at a time ([SiteLiveEdits]), including one still waiting when this owner is cleared.
 */
class CustomSitesViewModel(
    private val controller: CustomSitesController,
    /** The app's browser check page, which this opens for a site. */
    private val checks: SiteChecks,
    coroutineScope: CoroutineScope? = null,
    removalScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope
    private val removalScope = removalScope ?: scope
    private val removals = DeferredRemovals<String>(this.removalScope, ::commitRemoval)
    private val liveEdits = SiteLiveEdits(
        timerScope = scope,
        writeScope = this.removalScope,
        savedSite = { sourceId -> controller.sites.value.firstOrNull { it.sourceId == sourceId } },
        save = ::saveLive,
    )
    private val mutableState = MutableStateFlow(
        CustomSitesUiState(sites = controller.sites.value, builtInSites = controller.builtInSites.value),
    )
    val state: StateFlow<CustomSitesUiState> = mutableState.asStateFlow()

    /** The add form's probe or save; closing the form cancels it. */
    private var formJob: Job? = null

    private var testAllJob: Job? = null

    init {
        scope.launch {
            controller.sites.collect { sites ->
                val ids = sites.mapTo(HashSet()) { it.sourceId }
                mutableState.update { current ->
                    val editedSiteGone = (current.form as? SiteForm.Edit)?.sourceId?.let { it !in ids } == true
                    val builtInIds = current.builtInSites.mapTo(HashSet()) { it.source.id }
                    current.copy(
                        sites = sites,
                        retests = current.retests.filterKeys { it in ids || it in builtInIds },
                        form = if (editedSiteGone) null else current.form,
                    )
                }
            }
        }
    }

    init {
        scope.launch {
            removals.pending.collect { pending -> mutableState.update { it.copy(removals = pending) } }
        }
        scope.launch {
            controller.workingSourceIds.collect { ids -> mutableState.update { it.copy(workingSiteIds = ids) } }
        }
        scope.launch {
            controller.builtInSites.collect { sites -> mutableState.update { it.copy(builtInSites = sites) } }
        }
    }

    init {
        scope.launch {
            controller.setupRequest.filterNotNull().collect { request ->
                controller.clearSetupRequest()
                when (request) {
                    is SitesRequest.Add -> {
                        openForm(SiteForm.Add(address = request.address, linkAfterSetup = request.linkAfterSetup))
                        probe()
                    }
                    is SitesRequest.EnterKey -> startEdit(request.sourceId, expandKeys = true)
                }
            }
        }
    }

    fun onAction(action: CustomSitesAction) {
        when (action) {
            CustomSitesAction.StartAdd -> openForm(SiteForm.Add())
            is CustomSitesAction.StartEdit -> startEdit(action.sourceId, action.expandKeys)
            CustomSitesAction.CloseForm -> closeForm()
            is CustomSitesAction.OpenBuiltIn -> mutableState.update { it.copy(openBuiltIn = action.source, notice = null) }
            CustomSitesAction.CloseBuiltIn -> mutableState.update { it.copy(openBuiltIn = null) }
            is CustomSitesAction.SetAddress -> setAddress(action.value)
            is CustomSitesAction.SetLabel -> editField(LiveField.LABEL, ProbeReset.NEVER, TYPING_SAVE_DELAY) { copy(label = action.value) }
            is CustomSitesAction.SetUser -> setCredentialField(action.value) { copy(user = it) }
            is CustomSitesAction.SetApiKey -> setCredentialField(action.value) { copy(apiKey = it) }
            is CustomSitesAction.SetUserAgent -> editField(LiveField.USER_AGENT, ProbeReset.ALWAYS, TYPING_SAVE_DELAY) { copy(userAgent = action.value) }
            is CustomSitesAction.SetReferer -> editField(LiveField.REFERER, ProbeReset.ALWAYS, TYPING_SAVE_DELAY) { copy(referer = action.value) }
            is CustomSitesAction.SetIncludeInUnifiedAndForYou ->
                editField(LiveField.INCLUDE, ProbeReset.NEVER, Duration.ZERO) { copy(includeInUnifiedAndForYou = action.value) }
            CustomSitesAction.ShowCredentials -> updateAdd { copy(showCredentials = true) }
            is CustomSitesAction.SetClearCredentials -> updateEdit { copy(clearCredentials = action.value, message = null) }
            CustomSitesAction.Probe -> probe()
            CustomSitesAction.SaveNew -> saveNew(confirmReconnect = false)
            CustomSitesAction.ConfirmReconnect -> saveNew(confirmReconnect = true)
            CustomSitesAction.DismissReconnect -> dismissReconnect()
            CustomSitesAction.SaveEdit -> saveEdit()
            is CustomSitesAction.Retest -> retest(action.sourceId)
            CustomSitesAction.TestAll -> testAll()
            is CustomSitesAction.Remove -> remove(action.sourceId)
            is CustomSitesAction.ActOnRemoval -> removals.act(action.sourceId)
            CustomSitesAction.CommitRemovals -> removals.commitAll()
            CustomSitesAction.DismissNotice -> mutableState.update { it.copy(notice = null) }
            is CustomSitesAction.StartVerification -> startVerification(action.host)
        }
    }

    /**
     * Opens the app's browser check for [host]: a stored or built-in site's, or else the Add form's, whose site keeps
     * the browser's cookies until the form closes or saves. A pass fills the Add form, or updates the site's row.
     */
    private fun startVerification(host: String) {
        val source = state.value.sites.firstOrNull { it.host == host }?.let { SourceKey.parse(it.sourceId) }
            ?: state.value.builtInSites.firstOrNull { it.host == host }?.source
        if (source != null) {
            checks.open(source) { userAgent -> verified(source.id, userAgent) }
            return
        }
        if (state.value.form !is SiteForm.Add) return
        controller.setSetupHost(host)
        var probe: SiteProbeResult? = null
        checks.open(
            SiteCheckTarget(
                title = host,
                url = "https://$host/",
                passes = { userAgent -> probeAddTarget(userAgent).also { probe = it }?.clearedChallenge == true },
                complete = { userAgent -> updateAdd { verified(userAgent, probe) } },
            ),
        )
    }

    /** A stored or built-in site passed its check: its row shows its live status, and an open Edit form takes the agent. */
    private fun verified(sourceId: String, userAgent: String) = mutableState.update { current ->
        current.copy(
            retests = current.retests - sourceId,
            // The open Edit form's agent field follows, or a later Save would revert the saved agent.
            form = (current.form as? SiteForm.Edit)?.takeIf { it.sourceId == sourceId }
                ?.let { it.copy(fields = it.fields.copy(userAgent = userAgent)) } ?: current.form,
        )
    }

    /** The Add form's site passed its check with [userAgent]; [probe] is what the check found. */
    private fun SiteForm.Add.verified(userAgent: String, probe: SiteProbeResult?): SiteForm.Add =
        // A site that made the app prove itself may limit visitors without an account, so offer the key now.
        copy(fields = fields.copy(userAgent = userAgent), showCredentials = true).applyProbe(probe)

    private suspend fun probeAddTarget(userAgent: String): SiteProbeResult? {
        val form = state.value.form as? SiteForm.Add ?: return null
        val credentials = form.fields.credentials() as? FormInput.Ok ?: return null
        val headers = form.fields.headers() as? FormInput.Ok ?: return null
        return controller.probe(form.address, credentials.value, headers.value.copy(userAgent = userAgent))
    }

    override fun onCleared() {
        liveEdits.close()
        removals.commitAll()
    }

    private fun openForm(form: SiteForm) {
        liveEdits.flush()
        // An Add form's site stops sharing the browser's cookies once the form gives way.
        controller.setSetupHost(null)
        formJob?.cancel()
        mutableState.update { it.copy(form = form, notice = null) }
    }

    private fun startEdit(sourceId: String, expandKeys: Boolean) {
        val site = state.value.sites.firstOrNull { it.sourceId == sourceId } ?: return
        openForm(
            SiteForm.Edit(
                sourceId = sourceId,
                fields = SiteFields(
                    label = site.label,
                    userAgent = site.headers.userAgent.orEmpty(),
                    referer = site.headers.referer.orEmpty(),
                    includeInUnifiedAndForYou = site.includedInUnifiedAndForYou,
                ),
                hasCredentials = site.hasCredentials,
                expandKeys = expandKeys,
            ),
        )
    }

    private fun closeForm() {
        liveEdits.flush()
        // An Add form's site stops sharing the browser's cookies once the form gives way.
        controller.setSetupHost(null)
        formJob?.cancel()
        mutableState.update { it.copy(form = null) }
    }

    private fun setAddress(value: String) = updateAdd {
        // A different address is a different site, which the waiting link doesn't belong to.
        if (stage.busy) this else copy(address = value, stage = AddStage.Address(), linkAfterSetup = null)
    }

    /** Which field edits invalidate a finished probe, so a draft always matches what was checked. */
    private enum class ProbeReset { NEVER, ALWAYS, IF_CONFIRMING }

    private fun setFields(reset: ProbeReset, transform: SiteFields.() -> SiteFields) {
        mutableState.update { current ->
            val form = when (val form = current.form) {
                is SiteForm.Add -> if (form.stage.busy) form else form.copy(
                    fields = form.fields.transform(),
                    stage = if (reset.invalidates(form.stage)) AddStage.Address() else form.stage,
                )
                is SiteForm.Edit -> if (form.saving) form else form.copy(fields = form.fields.transform(), message = null)
                null -> null
            }
            current.copy(form = form)
        }
    }

    /** Changes a field; on the Edit page the change is saved after [wait] (typing restarts the wait). */
    private fun editField(field: LiveField, reset: ProbeReset, wait: Duration, transform: SiteFields.() -> SiteFields) {
        setFields(reset, transform)
        val form = state.value.form as? SiteForm.Edit ?: return
        if (form.saving) return
        // An invalid field waits unsaved (see SiteLiveEdits), so say why.
        val error = when {
            form.fields.label.isBlank() -> mutationMessage(CustomSiteMutation.InvalidLabel(SiteProfile.MAX_LABEL_LENGTH))
            form.fields.headers() is FormInput.Invalid -> HEADER_ERROR_MESSAGE
            else -> null
        }
        if (error != null) updateEdit { copy(message = error) }
        val fields = form.fields.let { LiveFields(it.label, it.includeInUnifiedAndForYou, it.userAgent, it.referer) }
        liveEdits.schedule(form.sourceId, field, fields, wait)
    }

    /** Saves a live edit; a failure shows on the Edit page, or above the list once the page has closed. True once saved. */
    private suspend fun saveLive(sourceId: String, edit: CustomSiteEdit): Boolean {
        val result = runCatchingPreservingCancellation { controller.update(sourceId, edit) }.getOrNull()
        val message = if (result == null) UNEXPECTED_FAILURE_MESSAGE else mutationMessage(result)
        mutableState.update { current ->
            val editing = (current.form as? SiteForm.Edit)?.takeIf { it.sourceId == sourceId }
            when {
                editing != null -> current.copy(form = editing.copy(message = message))
                message != null -> current.copy(notice = message)
                else -> current
            }
        }
        return result == CustomSiteMutation.Success
    }

    /** A pasted snippet (`user_id=…&api_key=…` or `login=…&api_key=…`) in either field fills both. */
    private fun setCredentialField(value: String, assign: SiteFields.(String) -> SiteFields) {
        val snippet = parseCredentialSnippet(value)
        setFields(ProbeReset.IF_CONFIRMING) {
            snippet?.let { copy(user = it.user, apiKey = it.apiKey) } ?: assign(value)
        }
    }

    private fun ProbeReset.invalidates(stage: AddStage): Boolean = when (this) {
        ProbeReset.NEVER -> false
        ProbeReset.ALWAYS -> true
        ProbeReset.IF_CONFIRMING -> stage is AddStage.Confirm
    }

    private fun probe() {
        val form = state.value.form as? SiteForm.Add ?: return
        if (form.stage.busy) return
        val credentials = form.fields.credentials()
        val headers = form.fields.headers()
        val invalid = (credentials as? FormInput.Invalid)?.message ?: (headers as? FormInput.Invalid)?.message
        if (invalid != null || credentials !is FormInput.Ok || headers !is FormInput.Ok) {
            updateAdd { copy(stage = AddStage.Address(invalid)) }
            return
        }
        updateAdd { copy(stage = AddStage.Probing) }
        formJob = scope.launch {
            val result = runCatchingPreservingCancellation {
                controller.probe(form.address, credentials.value, headers.value)
            }.getOrNull()
            updateAdd { if (stage == AddStage.Probing) applyProbe(result) else this }
        }
    }

    private fun SiteForm.Add.applyProbe(result: SiteProbeResult?): SiteForm.Add = when (result) {
        null -> copy(stage = AddStage.Address(UNEXPECTED_FAILURE_MESSAGE))
        is SiteProbeResult.Supported -> copy(
            fields = fields.copy(label = result.suggestedLabel.trim().take(SiteProfile.MAX_LABEL_LENGTH)),
            stage = AddStage.Confirm(result),
        )
        is SiteProbeResult.CredentialsRequired ->
            copy(showCredentials = true, stage = AddStage.Credentials(result.family, result.rejected))
        is SiteProbeResult.Unreachable if result.reason == ProbeFailure.BLOCKED ->
            copy(stage = AddStage.Address(probeMessage(result), blockedHost = result.address.host))
        else -> copy(stage = AddStage.Address(probeMessage(result)))
    }

    private fun saveNew(confirmReconnect: Boolean) {
        val form = state.value.form as? SiteForm.Add ?: return
        val probe = when (val stage = form.stage) {
            is AddStage.Confirm -> stage.probe.takeUnless { confirmReconnect }
            is AddStage.Reconnect -> stage.probe.takeIf { confirmReconnect }
            else -> null
        } ?: return
        val credentials = form.fields.credentials()
        val headers = form.fields.headers()
        if (credentials !is FormInput.Ok || headers !is FormInput.Ok) {
            val message = (credentials as? FormInput.Invalid ?: headers as? FormInput.Invalid)?.message
            updateAdd { copy(stage = AddStage.Confirm(probe, message)) }
            return
        }
        val draft = CustomSiteDraft(
            probe = probe,
            label = form.fields.label.trim(),
            credentials = credentials.value,
            headers = headers.value,
            includeInUnifiedAndForYou = form.fields.includeInUnifiedAndForYou,
            confirmReconnect = confirmReconnect,
            linkAfterSetup = form.linkAfterSetup,
        )
        updateAdd { copy(stage = AddStage.Saving(probe)) }
        formJob = scope.launch {
            val result = runCatchingPreservingCancellation { controller.add(draft) }.getOrNull()
            mutableState.update { current ->
                val add = current.form as? SiteForm.Add
                when {
                    add == null || add.stage != AddStage.Saving(probe) -> current
                    result == CustomSiteMutation.Success -> current.copy(form = null)
                    result is CustomSiteMutation.ReconnectConfirmationNeeded ->
                        current.copy(form = add.copy(stage = AddStage.Reconnect(probe, result.hostLabel)))
                    else -> current.copy(
                        form = add.copy(stage = AddStage.Confirm(probe, result?.let(::mutationMessage) ?: UNEXPECTED_FAILURE_MESSAGE)),
                    )
                }
            }
            if (result == CustomSiteMutation.Success) controller.setSetupHost(null)
        }
    }

    /** Declining a reconnect adds nothing and returns to the confirm form. */
    private fun dismissReconnect() = updateAdd {
        (stage as? AddStage.Reconnect)?.let { copy(stage = AddStage.Confirm(it.probe)) } ?: this
    }

    /** Saves the API key section: new keys (checked right after) or clearing the saved ones. */
    private fun saveEdit() {
        val form = state.value.form as? SiteForm.Edit ?: return
        if (form.saving) return
        val credentials = if (form.clearCredentials) FormInput.Ok(CredentialEdit.Clear) else {
            when (val entered = form.fields.credentials()) {
                is FormInput.Ok -> FormInput.Ok(entered.value?.let(CredentialEdit::Replace) ?: CredentialEdit.Keep)
                is FormInput.Invalid -> entered
            }
        }
        if (credentials !is FormInput.Ok) {
            updateEdit { copy(message = (credentials as FormInput.Invalid).message) }
            return
        }
        val edit = CustomSiteEdit(credentials = credentials.value)
        updateEdit { copy(saving = true, message = null) }
        scope.launch {
            val result = runCatchingPreservingCancellation { controller.update(form.sourceId, edit) }.getOrNull()
            mutableState.update { current ->
                val editing = current.form as? SiteForm.Edit
                when {
                    editing == null || editing.sourceId != form.sourceId -> current
                    result == CustomSiteMutation.Success -> current.copy(form = null)
                    else -> current.copy(
                        form = editing.copy(saving = false, message = result?.let(::mutationMessage) ?: UNEXPECTED_FAILURE_MESSAGE),
                    )
                }
            }
            // New keys aren't checked on save, so check them right away and show the answer on the row.
            if (result == CustomSiteMutation.Success && edit.credentials is CredentialEdit.Replace) retest(form.sourceId)
        }
    }

    private fun retest(sourceId: String) {
        if (state.value.retests[sourceId] == RetestStatus.Running) return
        if (state.value.sites.none { it.sourceId == sourceId }) return
        mutableState.update { it.copy(retests = it.retests + (sourceId to RetestStatus.Running)) }
        scope.launch { runRetest(sourceId) }
    }

    private suspend fun runRetest(sourceId: String) {
        val result = runCatchingPreservingCancellation { controller.retest(sourceId) }.getOrNull()
        mutableState.update { current ->
            val site = current.sites.firstOrNull { it.sourceId == sourceId } ?: return@update current
            val status = result?.let { retestStatus(site, it) } ?: RetestStatus.Done(UNEXPECTED_FAILURE_MESSAGE, ok = false)
            current.copy(retests = current.retests + (sourceId to status))
        }
    }

    /** Tests every site in list order, one at a time, naming each on the status line as it goes. */
    private fun testAll() {
        if (testAllJob?.isActive == true) return
        val builtIns = state.value.builtInSites
        val added = state.value.shownSites
        val total = builtIns.size + added.size
        testAllJob = scope.launch {
            val builtInResults = builtIns.mapIndexed { index, site ->
                testing(site.source.id, SitesTestProgress(site.label, index + 1, total)) { runBuiltInTest(site) }
            }
            val addedResults = added.mapIndexed { index, site ->
                testing(site.sourceId, SitesTestProgress(site.label, builtIns.size + index + 1, total)) { runRetest(site.sourceId) }
            }
            val working = (builtInResults + addedResults).count { it }
            mutableState.update { it.copy(testProgress = null, testSummary = testSummaryLine(working, total)) }
        }
    }

    /** Runs one site's [test] under the status line; true when it left the site's row marked as passed. */
    private suspend fun testing(sourceId: String, progress: SitesTestProgress, test: suspend () -> Unit): Boolean {
        mutableState.update {
            it.copy(testProgress = progress, testSummary = null, retests = it.retests + (sourceId to RetestStatus.Running))
        }
        test()
        return (state.value.retests[sourceId] as? RetestStatus.Done)?.ok == true
    }

    private suspend fun runBuiltInTest(site: BuiltInSiteSummary) {
        val result = runCatchingPreservingCancellation { controller.testBuiltIn(site.source) }.getOrNull()
        val status = result?.let { builtInTestStatus(site, it) } ?: RetestStatus.Done(UNEXPECTED_FAILURE_MESSAGE, ok = false)
        mutableState.update { it.copy(retests = it.retests + (site.source.id to status)) }
    }

    /**
     * Hides the site at once with Undo in its row; the site and its keys are removed only when the Undo times out
     * or Sites is left, so Undo never has to write secrets back from memory.
     */
    private fun remove(sourceId: String) {
        val site = state.value.sites.firstOrNull { it.sourceId == sourceId } ?: return
        if ((state.value.form as? SiteForm.Edit)?.sourceId == sourceId) closeForm()
        mutableState.update { it.copy(notice = null) }
        removals.remove(sourceId, "Removed ${site.label}")
    }

    /** Removes the site; a failure shows its message and leaves Retry in the row. */
    private suspend fun commitRemoval(sourceId: String): Boolean {
        val result = runCatchingPreservingCancellation { controller.remove(sourceId) }.getOrNull()
        val failure = if (result == null) UNEXPECTED_FAILURE_MESSAGE else mutationMessage(result)
        if (failure != null) mutableState.update { it.copy(notice = failure) }
        return failure == null
    }

    private fun updateAdd(transform: SiteForm.Add.() -> SiteForm.Add) = mutableState.update { current ->
        (current.form as? SiteForm.Add)?.let { current.copy(form = it.transform()) } ?: current
    }

    private fun updateEdit(transform: SiteForm.Edit.() -> SiteForm.Edit) = mutableState.update { current ->
        (current.form as? SiteForm.Edit)?.let { current.copy(form = it.transform()) } ?: current
    }
}

/** True while the add flow waits on the controller or the user's reconnect answer. */
internal val AddStage.busy: Boolean
    get() = this is AddStage.Probing || this is AddStage.Saving || this is AddStage.Reconnect

/** A parsed form value, or the message explaining why it can't be used. */
private sealed interface FormInput<out T> {
    data class Ok<T>(val value: T) : FormInput<T>
    data class Invalid(val message: String) : FormInput<Nothing>
}

/** Both fields blank means no keys; one blank is a mistake worth pointing out. */
private fun SiteFields.credentials(): FormInput<SiteCredentials?> {
    val user = user.trim()
    val apiKey = apiKey.trim()
    return when {
        user.isEmpty() && apiKey.isEmpty() -> FormInput.Ok(null)
        user.isEmpty() || apiKey.isEmpty() -> FormInput.Invalid(PARTIAL_CREDENTIALS_MESSAGE)
        else -> FormInput.Ok(SiteCredentials(user, apiKey))
    }
}

/** Blank fields use the defaults; [SiteHeaders] rejects multi-line or oversized values. */
private fun SiteFields.headers(): FormInput<SiteHeaders> = runCatching {
    SiteHeaders(userAgent = userAgent.trim().ifEmpty { null }, referer = referer.trim().ifEmpty { null })
}.fold(onSuccess = { FormInput.Ok(it) }, onFailure = { FormInput.Invalid(HEADER_ERROR_MESSAGE) })
