package com.theoriacodex.app.screenshots

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.theoriacodex.app.series.LibraryOptions
import com.theoriacodex.app.series.LibraryShow
import com.theoriacodex.app.series.LibrarySort
import com.theoriacodex.app.series.SeriesLibraryPreferences
import com.theoriacodex.app.series.SeriesTabScreen
import com.theoriacodex.app.series.SeriesTabViewModel
import com.theoriacodex.app.series.StoredOnly
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.domain.model.SortChoice
import com.theoriacodex.domain.model.SortDirection
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.extensionSourceKey
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeriesFollowingScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val followed = listOf(
        followed(1, "Paper Lantern", unread = 3, added = 6),
        followed(2, "Quiet Harbor", unread = 128, added = 5),
        followed(3, "Salt Road", unread = 0, added = 4),
        followed(4, "Moss Lane", unread = 38, added = 3),
        followed(5, "Dune Step", unread = 12, added = 2),
        followed(6, "Fern Gate", unread = 0, added = 1),
    )

    private val downloads = mapOf(followed[2].series.id to 4, followed[3].series.id to 5, followed[5].series.id to 1)

    @Test fun `series following`() = capture(LibraryOptions(), "series_following.png")

    @Test fun `series following downloaded`() =
        capture(LibraryOptions(SortChoice(LibrarySort.RECENTLY_ADDED, SortDirection.ASCENDING), LibraryShow.DOWNLOADED), "series_following_downloaded.png")

    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    @Test fun `series following narrow with both kinds`() =
        capture(LibraryOptions(SortChoice(LibrarySort.LAST_UPDATED, SortDirection.DESCENDING)), "series_following_narrow.png", followed + anime)

    @Config(qualifiers = "w384dp-h832dp-xxhdpi")
    @Test fun `series following anime fits its sort at 384dp`() =
        capture(LibraryOptions(), "series_following_384_anime.png", followed + anime, SeriesKind.ANIME)

    @Test fun `sort and filter sheet with default options`() =
        captureSheet(LibraryOptions(), "series_sort_filter_default.png")

    @Test fun `sort and filter sheet keeps its title when reset is available`() =
        captureSheet(LibraryOptions(show = LibraryShow.DOWNLOADED), "series_sort_filter_changed.png")

    private val anime = FollowedSeries(Series(SeriesId(SeriesKind.ANIME.extensionSourceKey(7), "/7"), "Kelp Row"), unreadCount = 9, followedAtEpochMs = 0, started = true)

    private fun capture(options: LibraryOptions, name: String, followed: List<FollowedSeries> = this.followed, kind: SeriesKind? = null) {
        show(options, followed, kind)
        compose.onRoot().captureRoboImage(name)
    }

    private fun captureSheet(options: LibraryOptions, name: String) {
        show(options, followed, kind = null)
        compose.onNodeWithContentDescription("Sort and filter").performClick()
        compose.waitForIdle()
        captureScreenRoboImage(name)
    }

    private fun show(options: LibraryOptions, followed: List<FollowedSeries>, kind: SeriesKind?) {
        val repository = object : SeriesRepository by StoredOnly(StoredSeries(followed.first().series), emptyList()) {
            override fun observeFollowed() = flowOf(followed)

            override fun observeHasFollowed() = flowOf(true)
        }
        val preferences = object : SeriesLibraryPreferences {
            override suspend fun read() = options

            override fun write(options: LibraryOptions) = Unit
        }
        val viewModel = SeriesTabViewModel(repository, { emptyList() }, library = preferences, downloads = flowOf(downloads)) { _, _ ->
            FollowedCheck(emptyList(), checked = 0)
        }
        kind?.let(viewModel::chooseKind)
        compose.setContent {
            TheoriaNightTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SeriesTabScreen(viewModel, onOpenSeries = {}, onOpenExtensions = {}, onOpenSettings = {})
                }
            }
        }
        compose.waitUntilSettled { viewModel.content.value.following != null }
        compose.waitForIdle()
    }

    private fun followed(id: Long, title: String, unread: Int, added: Long) =
        FollowedSeries(Series(SeriesId(SeriesKind.MANGA.extensionSourceKey(id), "/$id"), title), unreadCount = unread, followedAtEpochMs = added, started = true)
}
