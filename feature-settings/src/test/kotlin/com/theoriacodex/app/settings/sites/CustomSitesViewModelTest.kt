package com.theoriacodex.app.settings.sites

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.customsites.BuiltInSiteSummary
import com.theoriacodex.app.customsites.BuiltInTestResult
import com.theoriacodex.app.customsites.CredentialEdit
import com.theoriacodex.app.customsites.CustomSiteAvailability
import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.CustomSiteMutation
import com.theoriacodex.app.customsites.HeaderEdit
import com.theoriacodex.app.source.SiteChecks
import com.theoriacodex.app.ui.undo.DEFAULT_UNDO_DURATION
import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddressError
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProbeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CustomSitesViewModelTest {
    private val controller = FakeCustomSitesController()
    private lateinit var checks: SiteChecks
    private fun TestScope.owner() =
        CustomSitesViewModel(controller, siteChecks(controller, backgroundScope).also { checks = it }, backgroundScope).also { runCurrent() }

    private fun TestScope.probeAddress(owner: CustomSitesViewModel, input: String = "safebooru.org") {
        owner.onAction(CustomSitesAction.StartAdd)
        owner.onAction(CustomSitesAction.SetAddress(input))
        owner.onAction(CustomSitesAction.Probe)
        runCurrent()
    }

    private val CustomSitesViewModel.add get() = state.value.form as SiteForm.Add

    @Test fun `supported site prefills its name and saves with the opt-in off`() = runTest {
        controller.onProbe = { supported(label = "Safebooru") }
        val owner = owner()
        probeAddress(owner)
        assertEquals(AddStage.Confirm(supported()), owner.add.stage)
        assertEquals("Safebooru", owner.add.fields.label)
        assertEquals("safebooru.org", controller.probes.single().input)

        owner.onAction(CustomSitesAction.SetLabel("  My Safebooru "))
        owner.onAction(CustomSitesAction.SaveNew)
        runCurrent()

        val draft = controller.drafts.single()
        assertEquals("My Safebooru", draft.label)
        assertFalse(draft.includeInUnifiedAndForYou)
        assertFalse(draft.confirmReconnect)
        assertNull(draft.credentials)
        assertNull(owner.state.value.form)
        assertEquals(listOf("My Safebooru"), owner.state.value.sites.map { it.label })
    }

    @Test fun `probing shows progress and closing the form cancels it`() = runTest {
        val answer = CompletableDeferred<SiteProbeResult>()
        controller.onProbe = { answer.await() }
        val owner = owner()
        probeAddress(owner)
        assertEquals(AddStage.Probing, owner.add.stage)
        owner.onAction(CustomSitesAction.SetAddress("ignored.example"))
        assertEquals("safebooru.org", owner.add.address)

        owner.onAction(CustomSitesAction.CloseForm)
        answer.complete(supported())
        runCurrent()
        assertNull(owner.state.value.form)
    }

    @Test fun `credentials required shows key fields and re-probes with the entered keys`() = runTest {
        controller.onProbe = { call ->
            if (call.credentials == null) {
                SiteProbeResult.CredentialsRequired(address("gelbooru.example"), ProtocolFamily.GELBOORU_02, rejected = false)
            } else {
                supported("gelbooru.example", credentialsRequired = true)
            }
        }
        val owner = owner()
        probeAddress(owner, "gelbooru.example")
        assertTrue(owner.add.showCredentials)
        assertEquals(AddStage.Credentials(ProtocolFamily.GELBOORU_02, rejected = false), owner.add.stage)

        owner.onAction(CustomSitesAction.SetUser("42"))
        owner.onAction(CustomSitesAction.SetApiKey("secret"))
        owner.onAction(CustomSitesAction.Probe)
        runCurrent()
        assertEquals(SiteCredentials("42", "secret"), controller.probes.last().credentials)
        owner.onAction(CustomSitesAction.SaveNew)
        runCurrent()
        assertEquals(SiteCredentials("42", "secret"), controller.drafts.single().credentials)
    }

    @Test fun `rejected keys say so`() = runTest {
        controller.onProbe = {
            SiteProbeResult.CredentialsRequired(address("danbooru.example"), ProtocolFamily.DANBOORU, rejected = true)
        }
        val owner = owner()
        probeAddress(owner, "danbooru.example")
        assertEquals(AddStage.Credentials(ProtocolFamily.DANBOORU, rejected = true), owner.add.stage)
    }

    @Test fun `unsupported family names the family`() = runTest {
        controller.onProbe = { SiteProbeResult.UnsupportedFamily(address("sakugabooru.com"), "Moebooru") }
        val owner = owner()
        probeAddress(owner, "sakugabooru.com")
        assertEquals(
            AddStage.Address("This looks like a Moebooru site, which Theoria can't use yet."),
            owner.add.stage,
        )
    }

    @Test fun `not a booru and already built in let the user edit the address`() = runTest {
        val owner = owner()
        controller.onProbe = { SiteProbeResult.NotABooru(address("example.com")) }
        probeAddress(owner, "example.com")
        assertEquals(AddStage.Address("example.com doesn't answer like a supported booru."), owner.add.stage)

        controller.onProbe = { SiteProbeResult.AlreadyBuiltIn("DANBOORU") }
        probeAddress(owner, "danbooru.donmai.us")
        assertEquals(AddStage.Address("Danbooru is already built into Theoria."), owner.add.stage)
        assertEquals("This site is already built into Theoria.", alreadyBuiltInMessage("NOT_A_SOURCE"))
        owner.onAction(CustomSitesAction.SetAddress("safebooru.org"))
        assertEquals(AddStage.Address(), owner.add.stage)
    }

    @Test fun `every unreachable reason and address error has its own message`() = runTest {
        val owner = owner()
        val unreachable = ProbeFailure.entries.map { reason ->
            controller.onProbe = { SiteProbeResult.Unreachable(address("safebooru.org"), reason) }
            probeAddress(owner)
            (owner.add.stage as AddStage.Address).message.orEmpty()
        }
        val invalid = SiteAddressError.entries.map { error ->
            controller.onProbe = { SiteProbeResult.InvalidAddress(error) }
            probeAddress(owner, "http://safebooru.org")
            (owner.add.stage as AddStage.Address).message.orEmpty()
        }
        assertTrue(unreachable.all { it.contains("safebooru.org") })
        assertEquals(ProbeFailure.entries.size, unreachable.toSet().size)
        assertEquals(SiteAddressError.entries.size, invalid.filter(String::isNotEmpty).toSet().size)
        assertEquals("Only HTTPS sites can be added.", invalidAddressMessage(SiteAddressError.NOT_HTTPS))
    }

    @Test fun `reconnect asks first, cancel adds nothing, confirm retries with consent`() = runTest {
        controller.onProbe = { supported() }
        controller.onAdd = { draft ->
            if (draft.confirmReconnect) CustomSiteMutation.Success else CustomSiteMutation.ReconnectConfirmationNeeded("safebooru.org")
        }
        val owner = owner()
        probeAddress(owner)
        owner.onAction(CustomSitesAction.SaveNew)
        runCurrent()
        assertEquals(AddStage.Reconnect(supported(), "safebooru.org"), owner.add.stage)

        owner.onAction(CustomSitesAction.DismissReconnect)
        assertEquals(AddStage.Confirm(supported()), owner.add.stage)
        assertTrue(owner.state.value.sites.isEmpty())

        owner.onAction(CustomSitesAction.SaveNew)
        runCurrent()
        owner.onAction(CustomSitesAction.ConfirmReconnect)
        runCurrent()
        assertEquals(listOf(false, false, true), controller.drafts.map { it.confirmReconnect })
        assertNull(owner.state.value.form)
    }

    @Test fun `failed saves explain themselves on the confirm form`() = runTest {
        controller.onProbe = { supported() }
        val owner = owner()
        probeAddress(owner)
        val messages = listOf(
            CustomSiteMutation.LimitReached,
            CustomSiteMutation.DuplicateHost("custom_safebooru_org"),
            CustomSiteMutation.InvalidLabel(40),
            CustomSiteMutation.CredentialStoreUnavailable,
        ).map { result ->
            controller.onAdd = { result }
            owner.onAction(CustomSitesAction.SaveNew)
            runCurrent()
            (owner.add.stage as AddStage.Confirm).message
        }
        assertEquals(
            listOf(
                "You can add up to 32 sites. Remove one first.",
                "This site is already in your list.",
                "Use a name of 1 to 40 characters.",
                "Secure key storage isn't available right now. Try again.",
            ),
            messages,
        )
    }

    @Test fun `half-entered keys and bad headers are caught before the controller`() = runTest {
        val owner = owner()
        owner.onAction(CustomSitesAction.StartAdd)
        owner.onAction(CustomSitesAction.SetAddress("safebooru.org"))
        owner.onAction(CustomSitesAction.ShowCredentials)
        owner.onAction(CustomSitesAction.SetUser("42"))
        owner.onAction(CustomSitesAction.Probe)
        assertEquals(AddStage.Address(PARTIAL_CREDENTIALS_MESSAGE), owner.add.stage)

        owner.onAction(CustomSitesAction.SetUser(""))
        owner.onAction(CustomSitesAction.SetReferer("https://a.example/\nX-Evil: 1"))
        owner.onAction(CustomSitesAction.Probe)
        assertEquals(AddStage.Address(HEADER_ERROR_MESSAGE), owner.add.stage)
        assertTrue(controller.probes.isEmpty())
    }

    @Test fun `headers are probed with and saved, and editing one invalidates the probe`() = runTest {
        controller.onProbe = { supported() }
        val owner = owner()
        owner.onAction(CustomSitesAction.StartAdd)
        owner.onAction(CustomSitesAction.SetAddress("safebooru.org"))
        owner.onAction(CustomSitesAction.SetUserAgent(" Theoria/1 "))
        owner.onAction(CustomSitesAction.Probe)
        runCurrent()
        assertEquals(SiteHeaders(userAgent = "Theoria/1"), controller.probes.single().headers)

        owner.onAction(CustomSitesAction.SetReferer("https://safebooru.org/"))
        assertEquals(AddStage.Address(), owner.add.stage)
    }

    @Test fun `re-test reports working, a family change, or the failure`() = runTest {
        controller.sites.value = listOf(site())
        val owner = owner()
        val results = listOf(
            supported(),
            supported(family = ProtocolFamily.DANBOORU),
            SiteProbeResult.Unreachable(address("safebooru.org"), ProbeFailure.TIMEOUT),
        ).map { result ->
            controller.onRetest = { result }
            owner.onAction(CustomSitesAction.Retest("custom_safebooru_org"))
            runCurrent()
            owner.state.value.retests.getValue("custom_safebooru_org")
        }
        assertEquals(
            listOf(
                RetestStatus.Done("Working", ok = true),
                RetestStatus.Done("This site now answers as Danbooru. Remove it and add it again to switch.", ok = false),
                RetestStatus.Done("safebooru.org took too long to answer. Try again.", ok = false),
            ),
            results,
        )
    }

    @Test fun `edit saves the opt-in at once, the name once typing pauses, and only what changed`() = runTest {
        controller.sites.value = listOf(site(headers = SiteHeaders(referer = "https://safebooru.org/"), hasCredentials = true))
        val owner = owner()
        owner.onAction(CustomSitesAction.StartEdit("custom_safebooru_org"))
        val form = owner.state.value.form as SiteForm.Edit
        assertEquals("Safebooru", form.fields.label)
        assertEquals("https://safebooru.org/", form.fields.referer)

        owner.onAction(CustomSitesAction.SetIncludeInUnifiedAndForYou(true))
        runCurrent()
        assertEquals(CustomSiteEdit(includeInUnifiedAndForYou = true), controller.edits.single().second)

        owner.onAction(CustomSitesAction.SetLabel("Saf"))
        owner.onAction(CustomSitesAction.SetLabel("Safe"))
        owner.onAction(CustomSitesAction.SetReferer(""))
        advanceTimeBy(TYPING_SAVE_DELAY.inWholeMilliseconds - 1)
        assertEquals(1, controller.edits.size)
        advanceTimeBy(2)
        val (id, edit) = controller.edits.last()
        assertEquals("custom_safebooru_org", id)
        assertEquals(CustomSiteEdit(label = "Safe", referer = HeaderEdit.Replace(null)), edit)
        assertEquals(2, controller.edits.size)
        assertTrue(owner.state.value.form is SiteForm.Edit)
    }

    @Test fun `an agent typed beside an invalid referer is saved, and the referer once corrected`() = runTest {
        controller.sites.value = listOf(site())
        val owner = owner()
        owner.onAction(CustomSitesAction.StartEdit("custom_safebooru_org"))
        owner.onAction(CustomSitesAction.SetUserAgent("U1"))
        owner.onAction(CustomSitesAction.SetReferer("a\nb"))
        advanceTimeBy(TYPING_SAVE_DELAY.inWholeMilliseconds + 1)
        owner.onAction(CustomSitesAction.SetReferer("https://ref.example/"))
        owner.onAction(CustomSitesAction.CloseForm)
        runCurrent()
        val expected = listOf(CustomSiteEdit(userAgent = HeaderEdit.Replace("U1")), CustomSiteEdit(referer = HeaderEdit.Replace("https://ref.example/")))
        assertEquals(expected, controller.edits.map { it.second })
    }

    @Test fun `a blank name waits unsaved while the opt-in beside it is saved`() = runTest {
        controller.sites.value = listOf(site())
        val owner = owner()
        owner.onAction(CustomSitesAction.StartEdit("custom_safebooru_org"))
        owner.onAction(CustomSitesAction.SetLabel(" "))
        owner.onAction(CustomSitesAction.SetIncludeInUnifiedAndForYou(true))
        owner.onAction(CustomSitesAction.CloseForm)
        runCurrent()
        assertEquals(listOf(CustomSiteEdit(includeInUnifiedAndForYou = true)), controller.edits.map { it.second })
    }

    @Test fun `closing the edit page saves a name still waiting`() = runTest {
        controller.sites.value = listOf(site())
        val owner = owner()
        owner.onAction(CustomSitesAction.StartEdit("custom_safebooru_org"))
        owner.onAction(CustomSitesAction.SetLabel("Safer"))
        owner.onAction(CustomSitesAction.CloseForm)
        runCurrent()
        assertEquals(CustomSiteEdit(label = "Safer"), controller.edits.single().second)
        assertNull(owner.state.value.form)
    }

    @Test fun `changing a field back to its saved value drops the waiting save`() = runTest {
        controller.sites.value = listOf(site())
        val owner = owner()
        owner.onAction(CustomSitesAction.StartEdit("custom_safebooru_org"))
        owner.onAction(CustomSitesAction.SetLabel("Safer"))
        owner.onAction(CustomSitesAction.SetLabel("Safebooru"))
        owner.onAction(CustomSitesAction.CloseForm)
        advanceTimeBy(TYPING_SAVE_DELAY.inWholeMilliseconds + 1)
        assertTrue(controller.edits.isEmpty())
    }

    @Test fun `a change still waiting when the owner is cleared is saved in the scope that outlives it`() = runTest {
        controller.sites.value = listOf(site())
        val ownerScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val store = ViewModelStore()
        val owner = CustomSitesViewModel(controller, siteChecks(controller), ownerScope, backgroundScope)
        store.put("sites", owner)
        runCurrent()
        owner.onAction(CustomSitesAction.StartEdit("custom_safebooru_org"))
        owner.onAction(CustomSitesAction.SetLabel("Safer"))
        ownerScope.cancel()
        store.clear()
        runCurrent()
        assertEquals(CustomSiteEdit(label = "Safer"), controller.edits.single().second)
    }

    @Test fun `edit replaces keys and checks them, or clears them`() = runTest {
        controller.sites.value = listOf(site(availability = CustomSiteAvailability.NEEDS_CREDENTIALS, hasCredentials = true))
        controller.onRetest = { supported() }
        val owner = owner()
        owner.onAction(CustomSitesAction.StartEdit("custom_safebooru_org"))
        owner.onAction(CustomSitesAction.SetUser("7"))
        owner.onAction(CustomSitesAction.SetApiKey("new"))
        owner.onAction(CustomSitesAction.SaveEdit)
        runCurrent()
        assertEquals(RetestStatus.Done("Working", ok = true), owner.state.value.retests["custom_safebooru_org"])
        owner.onAction(CustomSitesAction.StartEdit("custom_safebooru_org"))
        owner.onAction(CustomSitesAction.SetClearCredentials(true))
        owner.onAction(CustomSitesAction.SaveEdit)
        runCurrent()
        assertEquals(
            listOf(CredentialEdit.Replace(SiteCredentials("7", "new")), CredentialEdit.Clear),
            controller.edits.map { it.second.credentials },
        )
    }

    @Test fun `remove hides the site at once and Undo brings it back without removing anything`() = runTest {
        controller.sites.value = listOf(site(), site("custom_other_example", "Other", "other.example"))
        val owner = owner()
        owner.onAction(CustomSitesAction.Remove("custom_safebooru_org"))
        runCurrent()
        assertEquals(listOf("Other"), owner.state.value.shownSites.map { it.label })
        assertEquals("Removed Safebooru", owner.state.value.removals["custom_safebooru_org"]?.label)

        owner.onAction(CustomSitesAction.ActOnRemoval("custom_safebooru_org"))
        runCurrent()
        assertEquals(listOf("Safebooru", "Other"), owner.state.value.shownSites.map { it.label })
        assertEquals(emptyList<String>(), controller.removals)
    }

    @Test fun `a removal happens when its Undo times out, and a failure shows a notice with Retry`() = runTest {
        controller.sites.value = listOf(site(), site("custom_other_example", "Other", "other.example"))
        val owner = owner()
        owner.onAction(CustomSitesAction.Remove("custom_safebooru_org"))
        advanceTimeBy(DEFAULT_UNDO_DURATION.inWholeMilliseconds + 1)
        assertEquals(listOf("custom_safebooru_org"), controller.removals)
        assertEquals(listOf("Other"), owner.state.value.sites.map { it.label })

        controller.onRemove = { CustomSiteMutation.NotFound }
        owner.onAction(CustomSitesAction.Remove("custom_other_example"))
        owner.onAction(CustomSitesAction.CommitRemovals)
        runCurrent()
        assertEquals("This site is no longer in your list.", owner.state.value.notice)
        assertEquals(PendingRemoval.Status.Failed, owner.state.value.removals["custom_other_example"]?.status)
    }

    @Test fun `a pasted key snippet in either field fills both`() = runTest {
        val owner = owner()
        owner.onAction(CustomSitesAction.StartAdd)
        owner.onAction(CustomSitesAction.SetApiKey("login=alice&api_key=k3y"))
        assertEquals("alice" to "k3y", owner.add.fields.user to owner.add.fields.apiKey)
        owner.onAction(CustomSitesAction.SetUser("https://g.example/index.php?page=account&api_key=abc&user_id=42"))
        assertEquals("42" to "abc", owner.add.fields.user to owner.add.fields.apiKey)
        owner.onAction(CustomSitesAction.SetUser("7"))
        assertEquals("7" to "abc", owner.add.fields.user to owner.add.fields.apiKey)
    }

    @Test fun `user hint names the family and flags a Gelbooru username`() {
        assertNull(userFieldHint(null, "x"))
        assertFalse(userFieldHint(ProtocolFamily.DANBOORU, "12")!!.error)
        assertFalse(userFieldHint(ProtocolFamily.GELBOORU_02, " 42 ")!!.error)
        assertTrue(userFieldHint(ProtocolFamily.GELBOORU_02, "alice")!!.error)
    }

    @Test fun `a setup request opens the add form with its address and checks it`() = runTest {
        controller.onProbe = { supported() }
        val owner = owner()
        controller.requestSetup("safebooru.org")
        runCurrent()
        assertEquals("safebooru.org", owner.add.address)
        assertEquals(AddStage.Confirm(supported()), owner.add.stage)
        assertNull(controller.setupRequest.value)
    }

    @Test fun `a link that led to setup travels with the saved site, unless the address changes`() = runTest {
        controller.onProbe = { supported() }
        val owner = owner()
        val link = "https://safebooru.org/index.php?page=post&s=view&id=7"

        controller.requestSetup("safebooru.org", link)
        runCurrent()
        owner.onAction(CustomSitesAction.SaveNew)
        runCurrent()
        assertEquals(link, controller.drafts.single().linkAfterSetup)

        controller.requestSetup("safebooru.org", link)
        runCurrent()
        owner.onAction(CustomSitesAction.SetAddress("other.example"))
        assertNull(owner.add.linkAfterSetup)
    }

    @Test fun `a key request opens that site's page with its key section open`() = runTest {
        controller.sites.value = listOf(site())
        val owner = owner()

        controller.requestKeyEntry("custom_safebooru_org")
        runCurrent()

        assertTrue((owner.state.value.form as SiteForm.Edit).expandKeys)
        assertNull(controller.setupRequest.value)
    }

    @Test fun `a key entered at the confirm step sends the form back to be checked`() = runTest {
        controller.onProbe = { supported() }
        val owner = owner()
        probeAddress(owner)
        assertEquals(AddStage.Confirm(supported()), owner.add.stage)

        owner.onAction(CustomSitesAction.SetApiKey("k"))

        assertEquals(AddStage.Address(), owner.add.stage)
    }

    @Test fun `Test all names each site in turn, built-in then added, and sums up how it went`() = runTest {
        val iwara = BuiltInSiteSummary(SourceKey.IWARA, "Iwara", "www.iwara.tv", working = false, needsVerification = false)
        controller.builtInSites.value = listOf(iwara)
        controller.sites.value = listOf(site())
        val builtInGate = CompletableDeferred<BuiltInTestResult>()
        val retestGate = CompletableDeferred<SiteProbeResult>()
        controller.onTestBuiltIn = { builtInGate.await() }
        controller.onRetest = { retestGate.await() }
        val owner = owner()

        owner.onAction(CustomSitesAction.TestAll)
        runCurrent()
        assertEquals(SitesTestProgress("Iwara", 1, 2), owner.state.value.testProgress)
        assertEquals(RetestStatus.Running, owner.state.value.retests[SourceKey.IWARA.id])

        builtInGate.complete(BuiltInTestResult.NEEDS_BROWSER_CHECK)
        runCurrent()
        assertEquals(SitesTestProgress("Safebooru", 2, 2), owner.state.value.testProgress)
        assertTrue((owner.state.value.retests[SourceKey.IWARA.id] as RetestStatus.Done).blocked)

        retestGate.complete(supported())
        runCurrent()
        assertNull(owner.state.value.testProgress)
        assertEquals("1 of 2 working", owner.state.value.testSummary)
    }

    /** Opens the check for [host] and lets the browser through a page with [userAgent]. */
    private fun TestScope.passCheck(owner: CustomSitesViewModel, host: String, userAgent: String = "WebView/1") {
        owner.onAction(CustomSitesAction.StartVerification(host))
        checks.pageLoaded(requireNotNull(checks.opened), userAgent, letThrough = true)
        runCurrent()
    }

    @Test fun `a stored or built-in site's check runs on the shared page and a pass reaches its row and Edit form`() = runTest {
        controller.sites.value = listOf(site(headers = SiteHeaders(userAgent = "Old/1")))
        controller.builtInSites.value = listOf(BuiltInSiteSummary(SourceKey.IWARA, "Iwara", "www.iwara.tv", working = false, needsVerification = true))
        controller.onCheckVerification = { _, _ -> true }
        val owner = owner()
        owner.onAction(CustomSitesAction.StartEdit("custom_safebooru_org"))

        passCheck(owner, "safebooru.org", userAgent = "WebView/2")
        passCheck(owner, "www.iwara.tv")

        assertNull(checks.opened)
        assertEquals(listOf(SourceKey.parseOrThrow("custom_safebooru_org") to "WebView/2", SourceKey.IWARA to "WebView/1"), controller.completions)
        assertEquals("WebView/2", (owner.state.value.form as SiteForm.Edit).fields.userAgent)
    }

    @Test fun `passing a browser check while adding offers the key fields, and the site keeps its cookies until it is saved`() = runTest {
        controller.onProbe = { call -> if (call.headers.userAgent == null) blocked else supported() }
        val owner = owner()
        probeAddress(owner)
        assertEquals(blocked.address.host, (owner.add.stage as AddStage.Address).blockedHost)

        passCheck(owner, "safebooru.org")

        assertNull(checks.opened)
        assertEquals("safebooru.org", controller.setupHosts.last())
        assertEquals("WebView/1", owner.add.fields.userAgent)
        assertTrue(owner.add.showCredentials)
        assertEquals(AddStage.Confirm(supported()), owner.add.stage)

        owner.onAction(CustomSitesAction.SaveNew)
        runCurrent()
        assertNull(controller.setupHosts.last())
    }

    private val blocked = SiteProbeResult.Unreachable(address("safebooru.org"), ProbeFailure.BLOCKED)
}
