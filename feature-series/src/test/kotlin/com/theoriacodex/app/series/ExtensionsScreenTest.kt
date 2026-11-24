package com.theoriacodex.app.series

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoListing
import com.theoriacodex.domain.series.SeriesExtensions
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
class ExtensionsScreenTest {
    @get:Rule val compose = createComposeRule()

    /** A first run leads from the empty page to adding a repo, and from a repo to Get extensions. */
    @Test fun `the empty page offers Add repo until a repo exists, which then leads to Get extensions`() {
        val repos = object : ExtensionRepos {
            val added = mutableListOf<ExtensionRepo>()
            override suspend fun repos() = added.toList()
            override suspend fun add(repos: List<NewExtensionRepo>): Map<String, String> {
                added += ExtensionRepo("https://repo.example", "Repo", "key")
                return emptyMap()
            }

            override suspend fun restore(repos: List<ExtensionRepo>) = emptyMap<String, String>()
            override suspend fun remove(url: String) = Unit
            override suspend fun available(refresh: Boolean) = RepoListing(emptyList())
            override suspend fun install(repo: String, packageName: String): String? = null
            override suspend fun uninstall(id: String) = Unit
        }
        val device = object : SeriesExtensions {
            override val revision = MutableStateFlow(0)
            override suspend fun installed() = emptyList<InstalledExtension>()
            override suspend fun setOn(id: String, signer: String, on: Boolean) = emptyList<InstalledExtension>()
            override suspend fun setLanguage(id: String, language: String, on: Boolean) = emptyList<InstalledExtension>()
            override suspend fun settings(id: String) = emptyList<ExtensionSourceSettings>()
            override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting) = emptyList<ExtensionSourceSettings>()
        }
        val vm = ExtensionsViewModel(device, repos)
        compose.setContent {
            TheoriaNightTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ExtensionsScreen(vm, onBack = {}) } }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Add repo").performClick()
        assertEquals(ExtensionsSubpage.REPOS, vm.state.value.subpage)
        assertEquals(AddRepo(), vm.state.value.addRepo)
        // The open dialog's cursor keeps Compose busy, so the repo is added without it.
        vm.askForRepo(false)
        vm.addRepo("https://repo.example")
        compose.waitUntil { vm.state.value.repos?.isNotEmpty() == true }
        compose.onNodeWithText("Get extensions").performClick()
        assertEquals(ExtensionsSubpage.BROWSE, vm.state.value.subpage)

        vm.openSubpage(null)
        compose.waitForIdle()
        compose.onNodeWithText("No extensions installed").assertExists()
        compose.onNodeWithText("Add repo").assertDoesNotExist()
    }
}
