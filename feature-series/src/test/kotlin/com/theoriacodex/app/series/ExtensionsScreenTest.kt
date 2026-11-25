package com.theoriacodex.app.series

import android.app.Application
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.RepoListing
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
class ExtensionsScreenTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetImages() = Coil.reset()

    @Test fun `the empty page offers Add repo until a repo exists, which then leads to Get extensions`() {
        val repos = FakeRepos()
        val vm = show(repos)
        compose.waitForIdle()
        compose.onNodeWithText("Add repo").performClick()
        assertEquals(ExtensionsSubpage.REPOS, vm.state.value.subpage)
        assertEquals(AddRepo(), vm.state.value.addRepo)
        vm.askForRepo(false)
        vm.addRepo("https://repo.example")
        compose.waitUntilSettled { vm.state.value.repos?.isNotEmpty() == true }
        compose.onNodeWithText("Get extensions").performClick()
        assertEquals(ExtensionsSubpage.BROWSE, vm.state.value.subpage)

        vm.openSubpage(null)
        compose.waitForIdle()
        compose.onNodeWithText("No extensions installed").assertExists()
        compose.onNodeWithText("Add repo").assertDoesNotExist()
    }

    @Test fun `each store row leads with its repo icon, or a same-size placeholder when the repo has none`() {
        val requested = mutableListOf<Any>()
        recordImageRequests(requested)
        val withIcon = RepoExtension("https://repo.example", "a.pkg", "Alpha", listOf("en"), "1.0", SeriesKind.MANGA, RepoExtension.Status.AVAILABLE, "https://repo.example/icon/a.pkg.png")
        val without = withIcon.copy(packageName = "b.pkg", name = "Beta", iconUrl = null)
        val vm = show(FakeRepos(listOf(withIcon, without)))
        vm.openSubpage(ExtensionsSubpage.BROWSE)
        compose.waitUntilSettled { vm.state.value.listing != null }

        val icons = compose.onAllNodesWithTag(EXTENSION_ICON_TAG).fetchSemanticsNodes()
        assertEquals(2, icons.size)
        val expected = with(compose.density) { 40.dp.roundToPx() }
        icons.forEach { assertEquals(IntSize(expected, expected), it.size) }
        compose.waitUntilSettled { requested.isNotEmpty() }
        assertEquals(listOf("https://repo.example/icon/a.pkg.png"), requested.map(Any::toString).distinct())
    }

    private fun show(repos: ExtensionRepos): ExtensionsViewModel {
        val vm = ExtensionsViewModel(NoDevice, repos)
        compose.setContent {
            TheoriaNightTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ExtensionsScreen(vm, onBack = {}) } }
        }
        compose.waitForIdle()
        return vm
    }

    private fun recordImageRequests(requested: MutableList<Any>) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val loader = ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).components {
            add(
                Fetcher.Factory<Any> { data, _, _ ->
                    Fetcher {
                        requested += data
                        DrawableResult(ColorDrawable(android.graphics.Color.MAGENTA), false, DataSource.MEMORY)
                    }
                },
            )
        }.build()
        Coil.setImageLoader(loader)
    }

    private class FakeRepos(private val offered: List<RepoExtension> = emptyList()) : ExtensionRepos {
        private val added = mutableListOf<ExtensionRepo>()
        override suspend fun repos() = added.toList()
        override suspend fun add(repos: List<NewExtensionRepo>): Map<String, String> {
            added += ExtensionRepo("https://repo.example", "Repo", "key")
            return emptyMap()
        }

        override suspend fun restore(repos: List<ExtensionRepo>) = emptyMap<String, String>()
        override suspend fun remove(url: String) = Unit
        override suspend fun available(refresh: Boolean) = RepoListing(offered)
        override suspend fun install(repo: String, packageName: String): String? = null
        override suspend fun uninstall(id: String) = Unit
    }

    private object NoDevice : SeriesExtensions {
        override val revision = MutableStateFlow(0)
        override suspend fun installed() = emptyList<InstalledExtension>()
        override suspend fun setOn(id: String, signer: String, on: Boolean) = emptyList<InstalledExtension>()
        override suspend fun setLanguage(id: String, language: String, on: Boolean) = emptyList<InstalledExtension>()
        override suspend fun settings(id: String) = emptyList<ExtensionSourceSettings>()
        override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting) = emptyList<ExtensionSourceSettings>()
    }
}
