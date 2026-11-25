package com.theoriacodex.app.series

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.data.repository.EntryProgressRestore
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoListing
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.extensionSourceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MissingExtensionsBackDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theSeriesTabExtensionsButtonTellsAccessibilityHowManySeriesNeedTheirExtension() {
        val tab = SeriesTabViewModel(Followed, { listOf(Loaded) }) { FollowedCheck(emptyList(), checked = 0, failed = 0) }
        compose.setContent { MaterialTheme { SeriesTabScreen(tab, onOpenSeries = {}, onOpenExtensions = {}) } }
        compose.waitUntil(TIMEOUT_MS) { tab.content.value.missingExtensions.isNotEmpty() }
        compose.waitForIdle()

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val heard = {
            automation.rootInActiveWindow?.let(::nodes).orEmpty()
                .filter { button -> button.isClickable && nodes(button).any { it.contentDescription == "Extensions" } }
                .map { "${it.stateDescription}" }
        }
        runCatching { compose.waitUntil(TIMEOUT_MS) { heard() == listOf("1 series needs its extension") } }
        assertEquals(listOf("1 series needs its extension"), heard())
        val shown = nodes(automation.rootInActiveWindow)
        assertEquals(0, shown.count { it.text?.contains("needs its extension") == true })
    }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(root) + (0 until root.childCount).mapNotNull(root::getChild).flatMap(::nodes)

    @Test
    fun aBackGestureFromASeriesOpenedThroughSwitchSourceReturnsToExtensionsWithTheSheetClosed() {
        lateinit var nav: NavHostController
        val missing = MissingSeriesViewModel(Followed, { listOf(Loaded) }, flowOf(Unit))
        val extensions = ExtensionsViewModel(NoExtensions, NoRepos)
        compose.setContent {
            nav = rememberNavController()
            MaterialTheme {
                NavHost(nav, startDestination = EXTENSIONS) {
                    composable(EXTENSIONS) {
                        val actions = MissingExtensionsActions(
                            missing = { missing },
                            switchSource = { id -> SwitchSourceViewModel(id, { listOf(Loaded) }, store = {}) { _, _ -> false } },
                            onOpenSeries = { nav.navigate(SERIES) },
                        )
                        ExtensionsScreen(extensions, onBack = {}, missing = actions)
                    }
                    composable(SERIES) { Text(SERIES_PAGE) }
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(MISSING).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(MISSING).performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Switch source").performClick()
        val match = hasText(SOURCE_NAME, substring = true) and hasClickAction()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(match).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(match).performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Open").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(SERIES_PAGE).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        val back = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread { back.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
        compose.waitForIdle()
        compose.runOnUiThread { back.dispatchOnBackProgressed(BackEventCompat(0f, 0f, GESTURE_PROGRESS, BackEventCompat.EDGE_LEFT)) }
        compose.waitForIdle()
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitUntil(TIMEOUT_MS) { nav.currentDestination?.route == EXTENSIONS }
        compose.waitForIdle()

        assertEquals(EXTENSIONS, nav.currentDestination?.route)
        compose.onAllNodesWithText(MISSING).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Close").assertCountEquals(0)
    }
}

private const val EXTENSIONS = "extensions"
private const val SERIES = "series"
private const val SERIES_PAGE = "Series page"
private const val MISSING = "Missing extensions"
private const val TITLE = "Kaiju"
private const val SOURCE_NAME = "Ink Harbor"
private const val TIMEOUT_MS = 10_000L
private const val GESTURE_PROGRESS = 0.5f

private object Loaded : SeriesSource {
    override val key: SourceKey = SeriesKind.MANGA.extensionSourceKey(1)
    override val name = SOURCE_NAME
    override val language = "en"
    override val supportsLatest = false
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), hasNextPage = false)
    override suspend fun latest(page: Int) = SeriesPage(emptyList(), hasNextPage = false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>) = SeriesPage(listOf(Series(SeriesId(key, "/kaiju"), TITLE)), hasNextPage = false)
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series, stored: List<SeriesEntry>) = SeriesEntries(emptyList())
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())
}

private object Followed : SeriesRepository {
    private val followed = MutableStateFlow(listOf(FollowedSeries(Series(SeriesId(SeriesKind.MANGA.extensionSourceKey(2), "/kaiju"), TITLE), unreadCount = 0)))
    override fun observeFollowed(): Flow<List<FollowedSeries>> = followed
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = emptyFlow()
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = emptyFlow()
    override suspend fun series(id: SeriesId): StoredSeries? = null
    override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> = emptyList()
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>, seriesMemo: String?) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun setChaptersShareFirstPage(id: SeriesId) = Unit
    override suspend fun setAllRead(id: SeriesId, read: Boolean) = EntryProgressRestore()
    override suspend fun setEntriesRead(id: SeriesId, entryAddresses: List<String>, read: Boolean) = EntryProgressRestore()
    override suspend fun restoreProgress(id: SeriesId, restore: EntryProgressRestore) = Unit
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?, pageOffset: Float?) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
}

private object NoExtensions : SeriesExtensions {
    override val revision = MutableStateFlow(0)
    override suspend fun installed() = emptyList<InstalledExtension>()
    override suspend fun setOn(id: String, signer: String, on: Boolean) = emptyList<InstalledExtension>()
    override suspend fun setLanguage(id: String, language: String, on: Boolean) = emptyList<InstalledExtension>()
    override suspend fun settings(id: String) = emptyList<ExtensionSourceSettings>()
    override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting) = emptyList<ExtensionSourceSettings>()
}

private object NoRepos : ExtensionRepos {
    override suspend fun repos() = emptyList<ExtensionRepo>()
    override suspend fun add(repos: List<NewExtensionRepo>) = emptyMap<String, String>()
    override suspend fun restore(repos: List<ExtensionRepo>) = emptyMap<String, String>()
    override suspend fun remove(url: String) = Unit
    override suspend fun available(refresh: Boolean) = RepoListing(emptyList())
    override suspend fun install(repo: String, packageName: String): String? = null
    override suspend fun uninstall(id: String) = Unit
}
