package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.customsites.CredentialEdit
import com.theoriacodex.app.customsites.CustomSiteAvailability
import com.theoriacodex.app.customsites.CustomSiteMutation
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddressError
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteHeaders
import com.theoriacodex.domain.source.SiteProbeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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

    private fun TestScope.owner() = CustomSitesViewModel(controller, backgroundScope).also { runCurrent() }

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

    @Test fun `edit prefills the site and sends label, keys, headers and opt-in`() = runTest {
        controller.sites.value = listOf(site(headers = SiteHeaders(referer = "https://safebooru.org/"), hasCredentials = true))
        val owner = owner()
        owner.onAction(CustomSitesAction.StartEdit("custom_safebooru_org"))
        val form = owner.state.value.form as SiteForm.Edit
        assertEquals("Safebooru", form.fields.label)
        assertEquals("https://safebooru.org/", form.fields.referer)

        owner.onAction(CustomSitesAction.SetLabel("Safe"))
        owner.onAction(CustomSitesAction.SetIncludeInUnifiedAndForYou(true))
        owner.onAction(CustomSitesAction.SetReferer(""))
        owner.onAction(CustomSitesAction.SaveEdit)
        runCurrent()
        val (id, edit) = controller.edits.single()
        assertEquals("custom_safebooru_org", id)
        assertEquals("Safe", edit.label)
        assertEquals(CredentialEdit.Keep, edit.credentials)
        assertEquals(SiteHeaders(), edit.headers)
        assertEquals(true, edit.includeInUnifiedAndForYou)
        assertNull(owner.state.value.form)
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

    @Test fun `remove asks first and a failure shows a notice`() = runTest {
        controller.sites.value = listOf(site(), site("custom_other_example", "Other", "other.example"))
        val owner = owner()
        owner.onAction(CustomSitesAction.RequestRemove("custom_safebooru_org"))
        assertEquals("Safebooru", owner.state.value.removeTarget?.label)
        owner.onAction(CustomSitesAction.ConfirmRemove)
        runCurrent()
        assertEquals(listOf("Other"), owner.state.value.sites.map { it.label })
        assertNull(owner.state.value.removeTarget)

        controller.onRemove = { CustomSiteMutation.NotFound }
        owner.onAction(CustomSitesAction.RequestRemove("custom_other_example"))
        owner.onAction(CustomSitesAction.ConfirmRemove)
        runCurrent()
        assertEquals("This site is no longer in your list.", owner.state.value.notice)
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

    @Test fun `a verification request opens that site and its browser check`() = runTest {
        controller.sites.value = listOf(site())
        val owner = owner()
        controller.requestVerification("custom_safebooru_org")
        runCurrent()
        assertEquals("custom_safebooru_org", (owner.state.value.form as SiteForm.Edit).sourceId)
        assertEquals("safebooru.org", owner.state.value.verifyingHost)
        assertNull(controller.setupRequest.value)
    }
}
