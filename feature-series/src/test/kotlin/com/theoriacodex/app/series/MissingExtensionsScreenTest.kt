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
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.series.ApkIcon
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SourceOff
import com.theoriacodex.domain.series.extensionSourceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    fun eachInstalledProviderSaysWhyItsSeriesAreMissingAndOffersOnlyTheActionThatHelps() {
        fun offered(packageName: String, name: String, source: Long, status: RepoExtension.Status = RepoExtension.Status.INSTALLED) =
            RepoExtension("https://repo.example", packageName, name, listOf("en"), "1.0", SeriesKind.MANGA, status, sources = listOf(SeriesKind.MANGA.extensionSourceKey(source)))
        fun installed(packageName: String, name: String, on: Boolean, problem: String? = null, removable: Boolean = false) =
            InstalledExtension("/apk/$packageName", name, "1.0", signer = "key", on = on, problem = problem, removable = removable, icon = ApkIcon(packageName, 1, "/apk/$packageName"))
        val repository = StubSeriesRepository((1L..6L).map { followed(SeriesKind.MANGA, it, "Series $it") })
        val repos = FakeExtensionRepos().apply {
            offered = listOf(
                offered("pkg.dex", "Ink Dex", 2),
                offered("pkg.reel", "Moon Reel", 3),
                offered("pkg.salt", "Salt Port", 4),
                offered("pkg.tide", "Tide Works", 5),
                offered("pkg.kelp", "Kelp", 6, RepoExtension.Status.UPDATE),
            )
        }
        val device = FakeSeriesExtensions(
            listOf(
                installed("pkg.dex", "Ink Dex", on = false),
                installed("pkg.reel", "Moon Reel", on = true),
                installed("pkg.salt", "Salt Port", on = true, problem = "Unsupported", removable = true),
                installed("pkg.tide", "Tide Works", on = true),
            ),
        ).apply { off = mapOf(SeriesKind.MANGA.extensionSourceKey(3) to SourceOff("Moon Reel", "es")) }
        val missing = MissingSeriesViewModel(repository, { listOf(FakeSeriesSource("custom_mihon_1", "Ink Harbor", "en", count = 1)) }, device.revision)
        val extensions = ExtensionsViewModel(device, repos)
        show(extensions, missing)
        compose.onNodeWithText(MISSING_EXTENSIONS_TITLE).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("1 series · $OFF").assertIsDisplayed()
        compose.onNodeWithText("1 series · ${languagesOffLine(listOf("es"))}").assertIsDisplayed()
        compose.onNodeWithText("1 series · $COULDNT_LOAD").assertIsDisplayed()
        compose.onNodeWithText("1 series · $NOT_IN_THIS_VERSION").assertIsDisplayed()
        compose.onNodeWithText("1 series").assertIsDisplayed()
        compose.onAllNodesWithText("Update").assertCountEquals(2)
        compose.onAllNodesWithText(TURN_ON).assertCountEquals(2)
        compose.onAllNodesWithText(OPEN).assertCountEquals(1)

        compose.onAllNodesWithText(TURN_ON)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Turn on Ink Dex?").assertIsDisplayed()
        compose.onAllNodesWithText(TURN_ON).onLast().performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("/apk/pkg.dex", "key", true)), device.switched)

        compose.onAllNodesWithText(TURN_ON)[1].performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("/apk/pkg.reel", "es", true)), device.languagesSet)

        compose.onNodeWithText(OPEN).performClick()
        compose.waitForIdle()
        assertEquals("/apk/pkg.salt", extensions.state.value.openId)
    }

    @Test
    fun titlesNoRepoListsGroupUnderTheirCoverSiteAndEveryTitleSaysHowFarItWasRead() {
        fun followed(id: Long, title: String, cover: String?, total: Int, unread: Int, started: Boolean) =
            FollowedSeries(Series(SeriesId(SeriesKind.MANGA.extensionSourceKey(id), "/$id"), title, coverUrl = cover), unreadCount = unread, started = started, entryCount = total)
        val repository = CountingRepository(
            listOf(
                followed(2, "Solo Climb", "https://cdn.asurascans.com/covers/2.webp", 40, 28, true),
                followed(3, "Iron Tide", "https://img.asurascans.com/covers/3.webp", 40, 40, false),
                followed(4, "Paper Moon", "https://static.flamecomics.xyz/4.jpg", 10, 0, true),
                followed(5, "Old Lantern", "/covers/5.jpg", 0, 0, false),
            ),
        )
        val missing = MissingSeriesViewModel(repository, { emptyList() }, flowOf(Unit))
        val extensions = ExtensionsViewModel(FakeSeriesExtensions(emptyList()), FakeExtensionRepos())
        show(extensions, missing)
        compose.onNodeWithText(MISSING_EXTENSIONS_TITLE).performClick()
        compose.waitForIdle()

        val order = listOf("asurascans.com", "Iron Tide", "Solo Climb", "flamecomics.xyz", "Paper Moon", "Old Lantern")
        val tops = order.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("12 of 40 chapters").assertIsDisplayed()
        compose.onNodeWithText("40 chapters · not started").assertIsDisplayed()
        compose.onNodeWithText("10 of 10 chapters").assertIsDisplayed()
        assertEquals(1, repository.followedQueries)
        assertEquals(0, repository.otherCalls)
    }

    private fun show(extensions: ExtensionsViewModel, missing: MissingSeriesViewModel) {
        val actions = MissingExtensionsActions({ missing }, { error("unused") }, onOpenSeries = {})
        compose.setContent { MaterialTheme { ExtensionsScreen(extensions, onBack = {}, missing = actions) } }
        compose.waitForIdle()
    }

    private fun followed(kind: SeriesKind, id: Long, title: String) = FollowedSeries(Series(SeriesId(kind.extensionSourceKey(id), "/$id"), title), unreadCount = 0)

    private class CountingRepository(private val followed: List<FollowedSeries>) : SeriesRepository by NothingStored {
        var followedQueries = 0
        var otherCalls = 0

        override fun observeFollowed(): Flow<List<FollowedSeries>> = flow {
            followedQueries++
            emit(followed)
        }

        override suspend fun series(id: SeriesId): StoredSeries? = null.also { otherCalls++ }

        override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> = emptyList<StoredSeriesEntry>().also { otherCalls++ }
    }
}
