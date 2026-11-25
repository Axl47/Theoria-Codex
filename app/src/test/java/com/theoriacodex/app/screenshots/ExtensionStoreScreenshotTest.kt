package com.theoriacodex.app.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import com.github.takahirom.roborazzi.captureRoboImage
import com.theoriacodex.app.series.ExtensionsScreen
import com.theoriacodex.app.series.ExtensionsSubpage
import com.theoriacodex.app.series.ExtensionsViewModel
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

    @Test fun `extension store`() {
        installSolidColorIcons()
        val vm = ExtensionsViewModel(NoDevice, FixtureRepos)
        compose.setContent {
            TheoriaNightTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ExtensionsScreen(vm, onBack = {}) } }
        }
        vm.openSubpage(ExtensionsSubpage.BROWSE)
        compose.waitUntilSettled { vm.state.value.listing != null }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("extension_store.png")
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

    private object NoDevice : SeriesExtensions {
        override val revision = MutableStateFlow(0)
        override suspend fun installed() = emptyList<InstalledExtension>()
        override suspend fun setOn(id: String, signer: String, on: Boolean) = emptyList<InstalledExtension>()
        override suspend fun setLanguage(id: String, language: String, on: Boolean) = emptyList<InstalledExtension>()
        override suspend fun settings(id: String) = emptyList<ExtensionSourceSettings>()
        override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting) = emptyList<ExtensionSourceSettings>()
    }
}

private const val REPO = "https://repo.example"
