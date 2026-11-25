package com.theoriacodex.app.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.theoriacodex.app.series.ExtensionsScreen
import com.theoriacodex.app.series.ExtensionsSubpage
import com.theoriacodex.app.series.ExtensionsViewModel
import com.theoriacodex.app.series.MissingExtensionsActions
import com.theoriacodex.app.series.MissingSeriesViewModel
import com.theoriacodex.app.series.StoredOnly
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.domain.series.ApkIcon
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.RepoListing
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.extensionSourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExtensionStoreScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetImages() = Coil.reset()

    @Test fun `extensions installed`() {
        installSolidColorIcons()
        val installed = listOf("Aurora", "Birch", "Cobalt", "Dune").mapIndexed { index, name ->
            val pkg = "fixture.${name.lowercase()}"
            InstalledExtension(pkg, name, "1.$index", signer = "key", on = index != 2, icon = ApkIcon(pkg, index.toLong(), "/data/app/$pkg.apk").takeIf { index != 3 })
        }
        val vm = ExtensionsViewModel(Device(installed), UpdatingRepos)
        compose.setContent {
            TheoriaNightTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ExtensionsScreen(vm, onBack = {}) } }
        }
        compose.waitUntilSettled { vm.state.value.extensions != null && vm.state.value.updates.isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("extensions_installed.png")
    }

    @Test fun `extension store`() {
        installSolidColorIcons()
        val vm = ExtensionsViewModel(Device(), FixtureRepos)
        compose.setContent {
            TheoriaNightTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ExtensionsScreen(vm, onBack = {}) } }
        }
        vm.openSubpage(ExtensionsSubpage.BROWSE)
        compose.waitUntilSettled { vm.state.value.listing != null }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("extension_store.png")
    }

    @Test fun `missing extensions`() {
        installSolidColorIcons()
        val followed = listOf(1L to "Paper Lantern", 2L to "Quiet Harbor", 3L to "Salt Road", 4L to "Moss Lane", 9L to "Tin Orchard").map { (id, title) ->
            FollowedSeries(Series(SeriesId(SeriesKind.MANGA.extensionSourceKey(id), "/$id"), title), unreadCount = 0)
        }
        val repository = object : SeriesRepository by StoredOnly(StoredSeries(followed.first().series), emptyList()) {
            override fun observeFollowed() = flowOf(followed)

            override fun observeHasFollowed() = flowOf(true)
        }
        val repos = object : ExtensionRepos by FixtureRepos {
            override suspend fun available(refresh: Boolean) = RepoListing(
                listOf(
                    RepoExtension(REPO, "fixture.aurora", "Aurora", listOf("en"), "1.0", SeriesKind.MANGA, RepoExtension.Status.AVAILABLE, "$REPO/icon/aurora.png", listOf(1L, 2L).map(SeriesKind.MANGA::extensionSourceKey)),
                    RepoExtension(REPO, "fixture.birch", "Birch", listOf("en"), "1.0", SeriesKind.MANGA, RepoExtension.Status.OTHER_KEY, "$REPO/icon/birch.png", listOf(SeriesKind.MANGA.extensionSourceKey(3))),
                    RepoExtension(REPO, "fixture.cedar", "Cedar", listOf("en"), "1.0", SeriesKind.MANGA, RepoExtension.Status.INSTALLED, "$REPO/icon/cedar.png", listOf(SeriesKind.MANGA.extensionSourceKey(4))),
                ),
            )
        }
        val cedar = InstalledExtension("/apk/cedar", "Cedar", "1.0", signer = "fixture-key", on = false, icon = ApkIcon("fixture.cedar", 1, "/apk/cedar"))
        val extensions = ExtensionsViewModel(Device(listOf(cedar)), repos)
        val missing = MissingSeriesViewModel(repository, { emptyList() }, flowOf(Unit))
        val actions = MissingExtensionsActions({ missing }, { error("unused") }, onOpenSeries = {})
        compose.setContent {
            TheoriaNightTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ExtensionsScreen(extensions, onBack = {}, missing = actions)
                }
            }
        }
        compose.waitUntilSettled { missing.state.value != null && extensions.state.value.extensions != null }
        compose.onNodeWithText("Missing extensions").performClick()
        compose.waitUntilSettled { extensions.state.value.listing != null }
        compose.onNodeWithText("Aurora").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("missing_extensions.png")
    }

    private fun installSolidColorIcons() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val loader = ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).components {
            add(
                Fetcher.Factory<Any> { data, _, _ ->
                    Fetcher {
                        val hue = (data.toString().hashCode() and Int.MAX_VALUE) % 360
                        val color = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.8f))
                        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                        DrawableResult(BitmapDrawable(context.resources, bitmap), false, DataSource.MEMORY)
                    }
                },
            )
        }.build()
        Coil.setImageLoader(loader)
    }

    private object FixtureRepos : ExtensionRepos {
        private val offered = listOf("Aurora", "Birch", "Cobalt", "Dune", "Ember", "Fjord", "Grove", "Harbor").mapIndexed { index, name ->
            val pkg = "fixture.${name.lowercase()}"
            RepoExtension(
                REPO, pkg, name, listOf(if (index % 3 == 0) "all" else "en"), "1.${index}", SeriesKind.MANGA,
                if (index == 1) RepoExtension.Status.INSTALLED else RepoExtension.Status.AVAILABLE,
                iconUrl = "$REPO/icon/$pkg.png".takeIf { index != 4 },
            )
        }

        override suspend fun repos() = listOf(ExtensionRepo(REPO, "Fixture", "key"))
        override suspend fun add(repos: List<NewExtensionRepo>) = emptyMap<String, String>()
        override suspend fun restore(repos: List<ExtensionRepo>) = emptyMap<String, String>()
        override suspend fun remove(url: String) = Unit
        override suspend fun available(refresh: Boolean) = RepoListing(offered)
        override suspend fun install(repo: String, packageName: String): String? = null
        override suspend fun uninstall(id: String) = Unit
    }

    private object UpdatingRepos : ExtensionRepos by FixtureRepos {
        override suspend fun available(refresh: Boolean) = RepoListing(
            listOf(RepoExtension(REPO, "fixture.aurora", "Aurora", listOf("en"), "1.1", SeriesKind.MANGA, RepoExtension.Status.UPDATE)),
        )
    }

    private class Device(private val installed: List<InstalledExtension> = emptyList()) : SeriesExtensions {
        override val revision = MutableStateFlow(0)
        override suspend fun installed() = installed
        override suspend fun setOn(id: String, signer: String, on: Boolean) = emptyList<InstalledExtension>()
        override suspend fun setLanguage(id: String, language: String, on: Boolean) = emptyList<InstalledExtension>()
        override suspend fun settings(id: String) = emptyList<ExtensionSourceSettings>()
        override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting) = emptyList<ExtensionSourceSettings>()
    }
}

private const val REPO = "https://repo.example"
