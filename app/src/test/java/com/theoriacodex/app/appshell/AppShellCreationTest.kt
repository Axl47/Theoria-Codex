package com.theoriacodex.app.appshell

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppShellCreationTest {
    @Test
    fun `Android saved-state factory creates and restores the startup owner`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val owner = ViewModelProvider(activity.get())[AppShellViewModel::class.java]
        assertSame(owner, ViewModelProvider(activity.get())[AppShellViewModel::class.java])
        owner.onAction(AppShellAction.AcceptIncomingUri("content://fixture/import.json", false, true))
        val pending = requireNotNull(owner.state.value.pendingIncomingUri)
        val savedState = Bundle()
        activity.saveInstanceState(savedState).pause().stop().destroy()

        val restored = Robolectric.buildActivity(ComponentActivity::class.java)
            .create(savedState).start().resume().visible()
        try {
            val restoredOwner = ViewModelProvider(restored.get())[AppShellViewModel::class.java]
            assertEquals(pending, restoredOwner.state.value.pendingIncomingUri)
        } finally {
            restored.pause().stop().destroy()
        }
    }
}
