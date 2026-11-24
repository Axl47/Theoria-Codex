package com.theoriacodex.app.series

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionsViewModelTest {
    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun theOpenPageFollowsAnExtensionRemovedAndInstalled() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val device = OnDevice(listOf(InstalledExtension(id = "/a.apk", name = "A", versionName = "1.0", signer = "key", on = false)))
        val viewModel = ExtensionsViewModel(device, Repos())
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

    @Test
    fun `an update installs, a failure shows on its row, and updates follow the repos`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repos = Repos()
        repos.offered = listOf(offered("a", RepoExtension.Status.UPDATE), offered("b", RepoExtension.Status.UPDATE), offered("c", RepoExtension.Status.AVAILABLE))
        repos.refusing = mapOf("b" to "Not signed with the repo's key")
        val viewModel = ExtensionsViewModel(OnDevice(emptyList()), repos)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), viewModel.state.value.updates.map { it.packageName })

        viewModel.updateAll()
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), repos.installed)
        assertEquals(listOf("b"), viewModel.state.value.updates.map { it.packageName })
        assertEquals(mapOf("b" to "Not signed with the repo's key"), viewModel.state.value.failures)
        assertEquals(emptySet<String>(), viewModel.state.value.installing)
    }

    @Test
    fun `a refused repo keeps the question open with why, and an added one closes it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repos = Repos()
        val viewModel = ExtensionsViewModel(OnDevice(emptyList()), repos)
        viewModel.askForRepo(true)

        viewModel.addRepo("https://bad.example")
        advanceUntilIdle()
        assertEquals(AddRepo(problem = "Not an extension repo"), viewModel.state.value.addRepo)

        viewModel.addRepo("https://repo.example")
        advanceUntilIdle()
        assertNull(viewModel.state.value.addRepo)
        assertEquals(listOf("https://repo.example"), viewModel.state.value.repos?.map { it.url })
    }

    @Test
    fun `browsing shows the device's languages and every-language extensions until a search looks through all`() {
        val extensions = listOf(offered("pt", languages = listOf("pt-BR")), offered("all", languages = listOf("all")), offered("ja", languages = listOf("ja")), offered("En2", languages = listOf("en")))
        assertEquals(listOf("all", "En2", "pt"), browseRows(extensions, "", listOf("en-US", "pt-PT")).map { it.name })
        assertEquals(listOf("ja"), browseRows(extensions, " J", listOf("en-US")).map { it.name })
    }

    private fun offered(name: String, status: RepoExtension.Status = RepoExtension.Status.AVAILABLE, languages: List<String> = listOf("en")) =
        RepoExtension("https://repo.example", name, name, languages, "1.0", SeriesKind.ANIME, status)

    /** A repo that offers [offered], refuses to install what [refusing] names, and adds only `https://repo.example`. */
    private class Repos : ExtensionRepos {
        var offered = emptyList<RepoExtension>()
        var refusing = emptyMap<String, String>()
        val installed = mutableListOf<String>()
        private val added = mutableListOf<ExtensionRepo>()

        override suspend fun repos() = added.toList()

        override suspend fun add(repos: List<NewExtensionRepo>): Map<String, String> {
            val (good, bad) = repos.partition { it.url == "https://repo.example" }
            good.forEach { added += ExtensionRepo(it.url, "Repo", "key") }
            return bad.associate { it.url to "Not an extension repo" }
        }

        override suspend fun remove(url: String) = Unit

        override suspend fun available(refresh: Boolean) = RepoListing(offered)

        override suspend fun install(repo: String, packageName: String): String? {
            installed += packageName
            refusing[packageName]?.let { return it }
            offered = offered.map { if (it.packageName == packageName) it.copy(status = RepoExtension.Status.INSTALLED) else it }
            return null
        }

        override suspend fun uninstall(id: String) = Unit
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
