package com.theoriacodex.app.series

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.domain.series.ApkIcon
import com.theoriacodex.domain.series.ExtensionLanguage
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.extensionSourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
class MissingExtensionsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun eachKindListsItsMissingSeriesFirstAndItsSheetGroupsThemByProviderUntilTheyResolve() {
        val repository = StubSeriesRepository(
            listOf(followed(SeriesKind.MANGA, 1, "Loaded"), followed(SeriesKind.MANGA, 2, "Kaiju"), followed(SeriesKind.MANGA, 3, "Dandadan"), followed(SeriesKind.MANGA, 9, "Blue Period"), followed(SeriesKind.ANIME, 5, "Moon Tide")),
        )
        val repos = FakeExtensionRepos().apply {
            offered = listOf(
                RepoExtension("https://repo.example", "pkg.dex", "Ink Dex", listOf("en"), "1.0", SeriesKind.MANGA, RepoExtension.Status.AVAILABLE, sources = listOf(2L, 3L).map(SeriesKind.MANGA::extensionSourceKey)),
            )
            problems = mapOf("https://down.example" to "Not found")
        }
        val device = FakeSeriesExtensions(emptyList())
        var loaded = listOf("custom_mihon_1")
        val missing = MissingSeriesViewModel(repository, { loaded.map { FakeSeriesSource(it, "Ink Harbor", "en", count = 1) } }, device.revision)
        val extensions = ExtensionsViewModel(device, repos)
        show(extensions, missing)

        compose.onNodeWithText("3 series").assertIsDisplayed()
        compose.onNodeWithText("1 series").assertIsDisplayed()
        compose.onAllNodesWithText(MISSING_EXTENSIONS_TITLE).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("2 series").assertIsDisplayed()
        compose.onAllNodesWithText("Moon Tide").assertCountEquals(0)
        compose.onNodeWithText(unreadReposLine(1)).assertIsDisplayed()
        compose.onNodeWithText(NOT_FOUND).assertIsDisplayed()
        repos.problems = emptyMap()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(unreadReposLine(1)).assertDoesNotExist()
        compose.onNodeWithText(NOT_IN_YOUR_REPOS).assertIsDisplayed()
        compose.onAllNodesWithText("Blue Period").assertCountEquals(1)
        compose.onAllNodesWithText("Kaiju").assertCountEquals(0)
        compose.onNodeWithText("Ink Dex").performClick()
        compose.onAllNodesWithText("Kaiju").assertCountEquals(1)

        compose.onAllNodesWithContentDescription("More options").onLast().performClick()
        compose.onNodeWithText(UNFOLLOW).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(NOT_IN_YOUR_REPOS).assertDoesNotExist()
        compose.onAllNodesWithText("Blue Period").assertCountEquals(0)

        compose.onNodeWithText("Install").performClick()
        compose.waitForIdle()
        assertEquals(listOf("pkg.dex"), repos.installed)
        loaded = listOf("custom_mihon_1", "custom_mihon_2", "custom_mihon_3")
        device.revision.value++
        compose.waitForIdle()
        compose.onNodeWithText("Install").assertDoesNotExist()
        compose.onAllNodesWithText(MISSING_EXTENSIONS_TITLE).assertCountEquals(1)
        compose.onNodeWithText("1 series").assertIsDisplayed()
    }

    @Test
    fun anInstalledExtensionThatIsOffTurnsOnThroughTheSignerQuestionAndALanguageOffOpensItsPage() {
        fun offered(packageName: String, name: String, source: Long) =
            RepoExtension("https://repo.example", packageName, name, listOf("en"), "1.0", SeriesKind.MANGA, RepoExtension.Status.INSTALLED, sources = listOf(SeriesKind.MANGA.extensionSourceKey(source)))
        fun installed(packageName: String, name: String, on: Boolean) =
            InstalledExtension("/apk/$packageName", name, "1.0", signer = "key", on = on, languages = listOf(ExtensionLanguage("es", false)), icon = ApkIcon(packageName, 1, "/apk/$packageName"))
        val repository = StubSeriesRepository(listOf(followed(SeriesKind.MANGA, 1, "Loaded"), followed(SeriesKind.MANGA, 2, "Kaiju"), followed(SeriesKind.MANGA, 3, "Dandadan")))
        val repos = FakeExtensionRepos().apply { offered = listOf(offered("pkg.dex", "Ink Dex", 2), offered("pkg.reel", "Moon Reel", 3)) }
        val device = FakeSeriesExtensions(listOf(installed("pkg.dex", "Ink Dex", on = false), installed("pkg.reel", "Moon Reel", on = true)))
        var loaded = listOf("custom_mihon_1")
        val missing = MissingSeriesViewModel(repository, { loaded.map { FakeSeriesSource(it, "Ink Harbor", "en", count = 1) } }, device.revision)
        val extensions = ExtensionsViewModel(device, repos)
        show(extensions, missing)
        compose.onNodeWithText(MISSING_EXTENSIONS_TITLE).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("1 series · $OFF").assertIsDisplayed()
        compose.onNodeWithText("1 series · $LANGUAGE_OFF").assertIsDisplayed()
        compose.onNodeWithText(TURN_ON).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Turn on Ink Dex?").assertIsDisplayed()
        compose.onAllNodesWithText(TURN_ON).onLast().performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("/apk/pkg.dex", "key", true)), device.switched)

        device.listed = listOf(installed("pkg.dex", "Ink Dex", on = true), installed("pkg.reel", "Moon Reel", on = true))
        loaded = listOf("custom_mihon_1", "custom_mihon_2")
        device.revision.value++
        compose.waitForIdle()
        compose.onNodeWithText("1 series · $OFF").assertDoesNotExist()
        compose.onNodeWithText(OPEN).performClick()
        compose.waitForIdle()
        assertEquals("/apk/pkg.reel", extensions.state.value.openId)
        compose.onNodeWithText("Languages").assertIsDisplayed()
    }

    private fun show(extensions: ExtensionsViewModel, missing: MissingSeriesViewModel) {
        val actions = MissingExtensionsActions({ missing }, { error("unused") }, onOpenSeries = {})
        compose.setContent { MaterialTheme { ExtensionsScreen(extensions, onBack = {}, missing = actions) } }
        compose.waitForIdle()
    }

    private fun followed(kind: SeriesKind, id: Long, title: String) = FollowedSeries(Series(SeriesId(kind.extensionSourceKey(id), "/$id"), title), unreadCount = 0)
}
