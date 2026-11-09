package com.theoriacodex.app.settings.sites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.customsites.CredentialEdit
import com.theoriacodex.app.customsites.CustomSiteDraft
import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteMutation
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.app.customsites.CustomSitesController
import com.theoriacodex.app.customsites.SitesRequest
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.sourceauth.parseCredentialSnippet
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
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

/**
 * Owner of Settings → Sites (plan C9). Turns [CustomSitesController] results into form stages and
 * plain messages; it never persists anything itself. Removing a site never touches library data.
 */
internal class CustomSitesViewModel(
    private val controller: CustomSitesController,
    coroutineScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope
    private val mutableState = MutableStateFlow(CustomSitesUiState(sites = controller.sites.value))
    val state: StateFlow<CustomSitesUiState> = mutableState.asStateFlow()

    /** The add form's probe or save; closing the form cancels it. */
    private var formJob: Job? = null

    /** The browser-check loop: the running checks, the one waiting after them, and the cookies last seen. */
    private var checkJob: Job? = null
    private var pendingCheck: VerificationCheck? = null
    private var seenCookies: CookieSnapshot? = null

    /** [explicit] for Done, which always completes on a pass; a page load only closes the page after a refusal. */
    private data class VerificationCheck(val userAgent: String, val explicit: Boolean)

    /** Wraps the cookies so that "none yet" differs from "nothing seen". */
    private data class CookieSnapshot(val cookies: String?)

    init {
        scope.launch {
            controller.sites.collect { sites ->
                val ids = sites.mapTo(HashSet()) { it.sourceId }
                mutableState.update { current ->
                    val editedSiteGone = (current.form as? SiteForm.Edit)?.sourceId?.let { it !in ids } == true
                    current.copy(
                        sites = sites,
                        retests = current.retests.filterKeys(ids::contains),
                        form = if (editedSiteGone) null else current.form,
                    )
                }
            }
        }
    }

    init {
        scope.launch {
            controller.setupRequest.filterNotNull().collect { request ->
                controller.clearSetupRequest()
                when (request) {
                    is SitesRequest.Add -> {
                        openForm(SiteForm.Add(address = request.address))
                        probe()
                    }
                    // A challenged source: open its browser check; completing it saves the agent and shows on the row.
                    is SitesRequest.Verify ->
                        state.value.sites.firstOrNull { it.sourceId == request.sourceId }?.let { startVerification(it.host, afterRefusal = true) }
                }
            }
        }
    }

    fun onAction(action: CustomSitesAction) {
        when (action) {
            CustomSitesAction.StartAdd -> openForm(SiteForm.Add())
            is CustomSitesAction.StartEdit -> startEdit(action.sourceId)
            CustomSitesAction.CloseForm -> closeForm()
            is CustomSitesAction.SetAddress -> setAddress(action.value)
            is CustomSitesAction.SetLabel -> setFields(ProbeReset.NEVER) { copy(label = action.value) }
            is CustomSitesAction.SetUser -> setCredentialField(action.value) { copy(user = it) }
            is CustomSitesAction.SetApiKey -> setCredentialField(action.value) { copy(apiKey = it) }
            is CustomSitesAction.SetUserAgent -> setFields(ProbeReset.ALWAYS) { copy(userAgent = action.value) }
            is CustomSitesAction.SetReferer -> setFields(ProbeReset.ALWAYS) { copy(referer = action.value) }
            is CustomSitesAction.SetIncludeInUnifiedAndForYou ->
                setFields(ProbeReset.NEVER) { copy(includeInUnifiedAndForYou = action.value) }
            CustomSitesAction.ShowCredentials -> updateAdd { copy(showCredentials = true) }
            is CustomSitesAction.SetClearCredentials -> updateEdit { copy(clearCredentials = action.value, message = null) }
            CustomSitesAction.Probe -> probe()
            CustomSitesAction.SaveNew -> saveNew(confirmReconnect = false)
            CustomSitesAction.ConfirmReconnect -> saveNew(confirmReconnect = true)
            CustomSitesAction.DismissReconnect -> dismissReconnect()
            CustomSitesAction.SaveEdit -> saveEdit()
            is CustomSitesAction.Retest -> retest(action.sourceId)
            is CustomSitesAction.RequestRemove -> mutableState.update { it.copy(removeTargetId = action.sourceId, notice = null) }
            CustomSitesAction.DismissRemove -> mutableState.update { if (it.removing) it else it.copy(removeTargetId = null) }
            CustomSitesAction.ConfirmRemove -> confirmRemove()
            CustomSitesAction.DismissNotice -> mutableState.update { it.copy(notice = null) }
            is CustomSitesAction.StartVerification -> startVerification(action.host, action.afterRefusal)
            CustomSitesAction.CancelVerification -> endVerification()
            is CustomSitesAction.ConfirmVerification -> requestCheck(VerificationCheck(action.userAgent, explicit = true))
            is CustomSitesAction.VerificationPageLoaded -> verificationPageLoaded(action.userAgent, action.cookies)
        }
    }

    /** Opens the browser check for [host] and lets its cookies through; a host with no saved site is the Add form's. */
    private fun startVerification(host: String, afterRefusal: Boolean) {
        endVerification()
        mutableState.update { it.copy(verification = SiteVerification(host, sawRefusal = afterRefusal), notice = null) }
        controller.setSetupHost(host)
    }

    /** Ends the browser check without completing it: stops its work and releases the setup host. */
    private fun endVerification() {
        checkJob?.cancel()
        pendingCheck = null
        seenCookies = null
        mutableState.update { it.copy(verification = null) }
        controller.setSetupHost(null)
    }

    /** A page finished loading: check only when the browser's cookies differ from the last ones checked. */
    private fun verificationPageLoaded(userAgent: String, cookies: String?) {
        if (seenCookies == CookieSnapshot(cookies)) return
        seenCookies = CookieSnapshot(cookies)
        requestCheck(VerificationCheck(userAgent, explicit = false))
    }

    /** One check runs at a time; a request made during one runs after it, and Done outranks a waiting page load. */
    private fun requestCheck(check: VerificationCheck) {
        if (state.value.verification == null) return
        if (pendingCheck?.explicit != true) pendingCheck = check
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            while (true) {
                val next = pendingCheck ?: break
                pendingCheck = null
                runVerificationCheck(next)
            }
        }
    }

    /**
     * Tests whether the app's own request gets through with the browser's agent; a check changes nothing.
     * A pass closes the page when the person said Done or a refusal is known, and only then completes:
     * a saved site records the agent, the Add form takes its probe. Otherwise the page stays open.
     */
    private suspend fun runVerificationCheck(check: VerificationCheck) {
        val host = state.value.verification?.host ?: return
        val site = state.value.sites.firstOrNull { it.host == host }
        updateVerification { copy(checking = true, message = if (check.explicit) null else message) }
        val result = runCatchingPreservingCancellation {
            if (site != null) controller.checkVerification(site.sourceId, check.userAgent) else probeAddTarget(check.userAgent)
        }.getOrNull()
        if (result?.clearedChallenge == true && verificationPassed(host, site, check, result)) return
        verificationRefused(host, check, result)
    }

    private suspend fun probeAddTarget(userAgent: String): SiteProbeResult? {
        val form = state.value.form as? SiteForm.Add ?: return null
        val credentials = form.fields.credentials() as? FormInput.Ok ?: return null
        val headers = form.fields.headers() as? FormInput.Ok ?: return null
        return controller.probe(form.address, credentials.value, headers.value.copy(userAgent = userAgent))
    }

    /** Returns false when completing failed, which then reads as a failed check. */
    private suspend fun verificationPassed(host: String, site: CustomSiteSummary?, check: VerificationCheck, result: SiteProbeResult): Boolean {
        val closes = check.explicit || state.value.verification?.sawRefusal == true
        if (!closes) {
            updateVerification {
                copy(checking = false, message = verificationAlreadyOpenMessage(host), messageIsError = false)
            }
            return true
        }
        if (site != null) {
            runCatchingPreservingCancellation { controller.completeVerification(site.sourceId, check.userAgent) }.getOrNull() ?: return false
        }
        mutableState.update { current ->
            if (current.verification == null) return@update current
            val applied = if (site != null) {
                // The open Edit form's agent field follows, or a later Save would revert the saved agent.
                current.copy(
                    retests = current.retests + (site.sourceId to retestStatus(site, result)),
                    form = (current.form as? SiteForm.Edit)?.takeIf { it.sourceId == site.sourceId }
                        ?.let { it.copy(fields = it.fields.copy(userAgent = check.userAgent)) } ?: current.form,
                )
            } else {
                (current.form as? SiteForm.Add)?.let { add ->
                    current.copy(form = add.copy(fields = add.fields.copy(userAgent = check.userAgent)).applyProbe(result))
                } ?: current
            }
            applied.copy(verification = null)
        }
        // A saved site's cookies need no setup host; the Add form's stay allowed until it closes or saves.
        if (site != null) controller.setSetupHost(null)
        return true
    }

    private fun verificationRefused(host: String, check: VerificationCheck, result: SiteProbeResult?) {
        val blocked = (result as? SiteProbeResult.Unreachable)?.reason == ProbeFailure.BLOCKED
        updateVerification {
            copy(
                checking = false,
                sawRefusal = sawRefusal || blocked,
                messageIsError = messageIsError || check.explicit,
                message = when {
                    !check.explicit -> message
                    blocked -> verificationRefusedMessage(host)
                    else -> result?.let(::probeMessage) ?: UNEXPECTED_FAILURE_MESSAGE
                },
            )
        }
    }

    private fun updateVerification(transform: SiteVerification.() -> SiteVerification) = mutableState.update { current ->
        current.copy(verification = current.verification?.transform())
    }

    private fun openForm(form: SiteForm) {
        endVerification()
        formJob?.cancel()
        mutableState.update { it.copy(form = form, notice = null) }
    }

    private fun startEdit(sourceId: String) {
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
            ),
        )
    }

    private fun closeForm() {
        endVerification()
        formJob?.cancel()
        mutableState.update { it.copy(form = null) }
    }

    private fun setAddress(value: String) = updateAdd {
        if (stage.busy) this else copy(address = value, stage = AddStage.Address())
    }

    /** Which field edits invalidate a finished probe, so a draft always matches what was checked. */
    private enum class ProbeReset { NEVER, ALWAYS, IF_KEYS_VERIFIED }

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

    /** A pasted snippet (`user_id=…&api_key=…` or `login=…&api_key=…`) in either field fills both. */
    private fun setCredentialField(value: String, assign: SiteFields.(String) -> SiteFields) {
        val snippet = parseCredentialSnippet(value)
        setFields(ProbeReset.IF_KEYS_VERIFIED) {
            snippet?.let { copy(user = it.user, apiKey = it.apiKey) } ?: assign(value)
        }
    }

    private fun ProbeReset.invalidates(stage: AddStage): Boolean = when (this) {
        ProbeReset.NEVER -> false
        ProbeReset.ALWAYS -> true
        ProbeReset.IF_KEYS_VERIFIED -> stage is AddStage.Confirm && stage.probe.credentialsRequired
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

    private fun saveEdit() {
        val form = state.value.form as? SiteForm.Edit ?: return
        if (form.saving) return
        val credentials = if (form.clearCredentials) FormInput.Ok(CredentialEdit.Clear) else {
            when (val entered = form.fields.credentials()) {
                is FormInput.Ok -> FormInput.Ok(entered.value?.let(CredentialEdit::Replace) ?: CredentialEdit.Keep)
                is FormInput.Invalid -> entered
            }
        }
        val headers = form.fields.headers()
        if (credentials !is FormInput.Ok || headers !is FormInput.Ok) {
            val message = (credentials as? FormInput.Invalid ?: headers as? FormInput.Invalid)?.message
            updateEdit { copy(message = message) }
            return
        }
        val edit = CustomSiteEdit(
            label = form.fields.label.trim(),
            credentials = credentials.value,
            headers = headers.value,
            includeInUnifiedAndForYou = form.fields.includeInUnifiedAndForYou,
        )
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
        scope.launch {
            val result = runCatchingPreservingCancellation { controller.retest(sourceId) }.getOrNull()
            mutableState.update { current ->
                val site = current.sites.firstOrNull { it.sourceId == sourceId } ?: return@update current
                val status = result?.let { retestStatus(site, it) } ?: RetestStatus.Done(UNEXPECTED_FAILURE_MESSAGE, ok = false)
                current.copy(retests = current.retests + (sourceId to status))
            }
        }
    }

    private fun confirmRemove() {
        val target = state.value.removeTargetId ?: return
        if (state.value.removing) return
        mutableState.update { it.copy(removing = true) }
        scope.launch {
            val result = runCatchingPreservingCancellation { controller.remove(target) }.getOrNull()
            mutableState.update { current ->
                val removed = result == CustomSiteMutation.Success
                val editingTarget = (current.form as? SiteForm.Edit)?.sourceId == target
                current.copy(
                    removeTargetId = null,
                    removing = false,
                    form = if (removed && editingTarget) null else current.form,
                    notice = if (removed) null else result?.let(::mutationMessage) ?: UNEXPECTED_FAILURE_MESSAGE,
                )
            }
        }
    }

    private fun updateAdd(transform: SiteForm.Add.() -> SiteForm.Add) = mutableState.update { current ->
        (current.form as? SiteForm.Add)?.let { current.copy(form = it.transform()) } ?: current
    }

    private fun updateEdit(transform: SiteForm.Edit.() -> SiteForm.Edit) = mutableState.update { current ->
        (current.form as? SiteForm.Edit)?.let { current.copy(form = it.transform()) } ?: current
    }

    companion object {
        fun factory(container: TheoriaAppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { CustomSitesViewModel(container.customSites) }
        }
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
