package com.theoriacodex.app.ui.routes

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteOwnerLifecycleTest {
    @Test
    fun `route owner lease rejects access after explicit close`() {
        val owner = RecordingOwner()
        val lease = WeakRouteOwnerLease(owner)

        lease.withOwner { activeOwner -> activeOwner.dispatchCount += 1 }
        lease.close()
        val result = lease.withOwner { activeOwner -> activeOwner.dispatchCount += 1 }

        assertEquals(1, owner.dispatchCount)
        assertNull(result)
    }

    @Test
    fun `view model clear closes its registered route owner lease`() {
        val viewModel = object : ViewModel() {}
        val lease = viewModel.createRouteOwnerLease()
        var closeCount = 0
        lease.invokeOnClose { closeCount += 1 }

        clearViewModel(viewModel)
        lease.close()

        assertNull(lease.withOwner { it })
        assertEquals(1, closeCount)
    }

    @Test
    fun `repeated route lease creation reuses one view model closeable and clears once`() {
        val viewModel = RecordingViewModel()
        val firstLease = viewModel.createRouteOwnerLease()
        val secondLease = viewModel.createRouteOwnerLease()
        var closeCount = 0
        firstLease.invokeOnClose { closeCount += 1 }

        assertSame(firstLease, secondLease)

        clearViewModel(viewModel)
        firstLease.close()
        secondLease.close()

        assertNull(firstLease.withOwner { it })
        assertEquals(1, closeCount)
    }

    @Test
    fun `view model clear removes the matching published state and actions`() {
        val viewModel = RecordingViewModel()
        val handle = LeaseBackedRecordingHandle(viewModel)
        var publishedState: StateFlow<String>? = null
        val binding = RouteOwnerHandleBinding<LeaseBackedRecordingHandle> { published ->
            publishedState = published?.state
        }

        binding.publish(handle)

        assertEquals("loaded results", publishedState?.value)
        assertTrue(handle.dispatch())

        clearViewModel(viewModel)

        assertNull(binding.current)
        assertNull(publishedState)
        assertNull(handle.state)
        assertFalse(handle.dispatch())
        assertEquals(1, viewModel.dispatchCount)
    }

    @Test
    fun `late old owner close cannot clear a newer published handle`() {
        val first = ManuallyClosingHandle()
        val second = ManuallyClosingHandle()
        var published: ManuallyClosingHandle? = null
        val binding = RouteOwnerHandleBinding<ManuallyClosingHandle> { handle -> published = handle }

        binding.publish(first)
        val inFlightOldClose = first.captureCloseSignal()
        binding.publish(second)

        inFlightOldClose()

        assertSame(second, binding.current)
        assertSame(second, published)

        second.closeOwner()

        assertNull(binding.current)
        assertNull(published)
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

    @Test
    fun `published route handles expose read only state flows`() {
        listOf(
            SearchRouteOwnerHandle::class.java,
            ForYouRouteOwnerHandle::class.java,
            CreatorRouteOwnerHandle::class.java,
        ).forEach { handleClass ->
            val stateGetter = handleClass.declaredMethods.single { method ->
                method.name.startsWith("getState")
            }
            assertTrue(
                "${handleClass.simpleName} must expose immutable StateFlow state",
                StateFlow::class.java.isAssignableFrom(stateGetter.returnType),
            )
        }
    }

    private fun clearViewModel(viewModel: ViewModel) {
        ViewModel::class.java.declaredMethods.single { method ->
            method.name.startsWith("clear") && method.parameterCount == 0
        }.apply {
            isAccessible = true
            invoke(viewModel)
        }
    }

    private class RecordingOwner(
        var dispatchCount: Int = 0,
    )

    private class RecordingViewModel : ViewModel() {
        val state = MutableStateFlow("loaded results")
        var dispatchCount = 0
    }

    private class LeaseBackedRecordingHandle(
        owner: RecordingViewModel,
    ) : ObservableRouteOwnerHandle {
        private val lease = owner.createRouteOwnerLease()

        val state: StateFlow<String>?
            get() = lease.withOwner { activeOwner -> activeOwner.state }

        fun dispatch(): Boolean {
            return lease.withOwner { activeOwner ->
                activeOwner.dispatchCount += 1
                true
            } ?: false
        }

        override fun invokeOnOwnerCleared(listener: () -> Unit): Closeable {
            return lease.invokeOnClose(listener)
        }
    }

    private class ManuallyClosingHandle : ObservableRouteOwnerHandle {
        private val listeners = linkedSetOf<() -> Unit>()

        override fun invokeOnOwnerCleared(listener: () -> Unit): Closeable {
            listeners += listener
            return Closeable { listeners -= listener }
        }

        /** Captures the callback as though closure had already copied it before replacement. */
        fun captureCloseSignal(): () -> Unit {
            val captured = listeners.toList()
            return { captured.forEach { listener -> listener() } }
        }

        fun closeOwner() {
            val captured = listeners.toList()
            listeners.clear()
            captured.forEach { listener -> listener() }
        }
    }
}
