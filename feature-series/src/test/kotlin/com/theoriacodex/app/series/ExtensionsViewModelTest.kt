package com.theoriacodex.app.series

import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.SeriesExtensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionsViewModelTest {
    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun theOpenPageFollowsAnExtensionRemovedAndInstalled() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val device = OnDevice(listOf(InstalledExtension(id = "/a.apk", name = "A", versionName = "1.0", signer = "key", on = false)))
        val viewModel = ExtensionsViewModel(device)
        advanceUntilIdle()
        assertEquals(listOf("A"), viewModel.state.value.extensions?.map { it.name })

        val removed = device.listed.also { device.listed = emptyList() }
        device.revision.value++
        advanceUntilIdle()
        assertEquals(emptyList<InstalledExtension>(), viewModel.state.value.extensions)

        device.listed = removed
        device.revision.value++
        advanceUntilIdle()
        assertEquals(removed, viewModel.state.value.extensions)
    }

    /** Lists [listed]; the test moves [revision] as the host does when a package changes. */
    private class OnDevice(var listed: List<InstalledExtension>) : SeriesExtensions {
        override val revision = MutableStateFlow(0)

        override suspend fun installed() = listed

        override suspend fun setOn(id: String, signer: String, on: Boolean) = listed

        override suspend fun setLanguage(id: String, language: String, on: Boolean) = listed

        override suspend fun settings(id: String) = emptyList<ExtensionSourceSettings>()

        override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting) = emptyList<ExtensionSourceSettings>()
    }
}
