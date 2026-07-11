package com.theoriacodex.app.ui.routes

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        clearViewModel(viewModel)

        assertNull(lease.withOwner { it })
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
}
