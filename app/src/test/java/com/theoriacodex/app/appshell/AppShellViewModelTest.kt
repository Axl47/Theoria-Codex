package com.theoriacodex.app.appshell

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.update.StartupUpdateState
import com.theoriacodex.app.update.StartupUpdateWorkflowEffect
import com.theoriacodex.app.update.StartupUpdateWorkflowEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppShellViewModelTest {
    @Test
    fun `incoming handoff is retained and only current value is consumed`() {
        val handle = SavedStateHandle()
        val owner = AppShellViewModel(handle)
        owner.onAction(AppShellAction.AcceptIncomingUri("content://first", false, true))
        val first = requireNotNull(owner.state.value.pendingIncomingUri)
        owner.onAction(AppShellAction.AcceptIncomingUri("https://example.test/post", false, false))
        val current = requireNotNull(owner.state.value.pendingIncomingUri)

        owner.onAction(AppShellAction.ConsumeIncomingUri(first))
        assertEquals(current, owner.state.value.pendingIncomingUri)

        owner.onAction(AppShellAction.ConsumeIncomingUri(current))
        assertNull(owner.state.value.pendingIncomingUri)
        assertNull(AppShellViewModel(handle).state.value.pendingIncomingUri)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `startup transition publishes one shot shell effect`() = runTest {
        val owner = AppShellViewModel(SavedStateHandle())
        val received = async { owner.effects.first() }
        runCurrent()

        owner.onAction(AppShellAction.UpdateStartup(StartupUpdateWorkflowEvent.NoEligibleUpdate))

        assertEquals(StartupUpdateState.NoUpdate, owner.state.value.startup.updateState)
        assertEquals(
            AppShellEffect.Startup(StartupUpdateWorkflowEffect.ContinueToApp),
            received.await(),
        )
    }

    @Test
    fun `completed startup survives shell reconstruction without feature coordinator sentinels`() {
        val handle = SavedStateHandle()
        val owner = AppShellViewModel(handle)

        owner.onAction(AppShellAction.MarkAppReady)

        assertEquals(true, owner.state.value.appReady)
        assertEquals(true, AppShellViewModel(handle).state.value.appReady)
    }
}
