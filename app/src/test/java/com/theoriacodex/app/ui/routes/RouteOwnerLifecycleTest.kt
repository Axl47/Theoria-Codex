package com.theoriacodex.app.ui.routes

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RouteOwnerLifecycleTest {
    @Test
    fun `view model clear empties the slot that published it`() {
        val viewModel = RecordingViewModel()
        val slot = RouteOwnerSlot<RecordingViewModel>()

        slot.publish(viewModel)
        assertSame(viewModel, slot.current)

        clearViewModel(viewModel)

        assertNull(slot.current)
    }

    @Test
    fun `an old owner's clear cannot empty a slot holding its replacement`() {
        val first = RecordingViewModel()
        val second = RecordingViewModel()
        val slot = RouteOwnerSlot<RecordingViewModel>()

        slot.publish(first)
        slot.publish(second)
        clearViewModel(first)

        assertSame(second, slot.current)

        clearViewModel(second)

        assertNull(slot.current)
    }

    @Test
    fun `republishing one owner keeps one closeable on it, and several slots each empty on its clear`() {
        val viewModel = RecordingViewModel()
        val shellSlot = RouteOwnerSlot<RecordingViewModel>()
        val recreatedShellSlot = RouteOwnerSlot<RecordingViewModel>()

        shellSlot.publish(viewModel)
        val signal = viewModel.getCloseable<AutoCloseable>("route-owner-cleared")
        repeat(2) { shellSlot.publish(viewModel) }
        recreatedShellSlot.publish(viewModel)
        assertSame(signal, viewModel.getCloseable<AutoCloseable>("route-owner-cleared"))

        clearViewModel(viewModel)

        assertNull(shellSlot.current)
        assertNull(recreatedShellSlot.current)
    }

    @Test
    fun `an owner cleared before publication leaves the slot empty`() {
        val viewModel = RecordingViewModel()
        clearViewModel(viewModel)
        val slot = RouteOwnerSlot<RecordingViewModel>()

        slot.publish(viewModel)

        assertNull(slot.current)
    }

    @Test
    fun `a cleared slot ignores its former owner's later clear`() {
        val viewModel = RecordingViewModel()
        val slot = RouteOwnerSlot<RecordingViewModel>()
        slot.publish(viewModel)
        slot.clear()
        val replacement = RecordingViewModel()
        slot.publish(replacement)

        clearViewModel(viewModel)

        assertSame(replacement, slot.current)
    }

    @Test
    fun `search resume observer delivers immediately when attached already resumed`() {
        var resumeCount = 0
        val observer = SearchRouteResumeObserver { resumeCount += 1 }

        observer.synchronize(Lifecycle.State.RESUMED)
        observer.onLifecycleEvent(Lifecycle.Event.ON_RESUME)

        assertEquals(1, resumeCount)
    }

    @Test
    fun `search resume observer delivers once in each resume period`() {
        var resumeCount = 0
        val observer = SearchRouteResumeObserver { resumeCount += 1 }

        observer.synchronize(Lifecycle.State.STARTED)
        observer.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        observer.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        observer.onLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        observer.onLifecycleEvent(Lifecycle.Event.ON_RESUME)

        assertEquals(2, resumeCount)
    }

    private fun clearViewModel(viewModel: ViewModel) {
        ViewModel::class.java.declaredMethods.single { method ->
            method.name.startsWith("clear") && method.parameterCount == 0
        }.apply {
            isAccessible = true
            invoke(viewModel)
        }
    }

    private class RecordingViewModel : ViewModel()
}
