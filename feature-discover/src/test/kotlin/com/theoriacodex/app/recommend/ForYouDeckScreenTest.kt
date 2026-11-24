package com.theoriacodex.app.recommend

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.related.testRelatedShelf
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.search.sortModeLabel
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.trails.TrailPage
import com.theoriacodex.app.trails.TrailPager
import com.theoriacodex.app.triage.TriageCardSupport
import com.theoriacodex.app.ui.components.FeedDurations
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.ui.navigation.HiddenTagsOpener
import com.theoriacodex.app.ui.navigation.LocalHiddenTagsOpener
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The For You screen over a real owner and fakes that answer at once: the deck, its trails, and the grid toggle. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForYouDeckScreenTest {
    @get:Rule val compose = createComposeRule()

    private val engine = FakeForYouEngine(listOf("fog", "sea"))
    private val trails = FakeTrails()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settings = AppSettings()
    private var grid by mutableStateOf(false)
    private var sort by mutableStateOf(SortMode.NEWEST)
    private var animated by mutableStateOf(false)

    /** Each Viewer launch, as the level's id and the post. */
    private val opened = mutableListOf<Pair<String, PostId>>()
    private val savedSearches = mutableStateListOf<Query>()
    private val selectedProfiles = mutableListOf<String>()
    private var wentToSearch = 0
    private var sourcesOpened = 0
    private var hiddenTagsOpened = 0

    @After fun tearDown() = writeScope.cancel()

    private fun environment() = deckEnvironment(settings, grid, creatorBrowsingSources = setOf(DECK_SOURCE))

    private fun render(): ForYouDeckViewModel {
        val owner = ForYouDeckViewModel(engine, deckWiring(writeScope, trails)).also { it.synchronize(environment()) }
        val callbacks = ForYouDeckCallbacks(
            onSelectProfile = { selectedProfiles += it },
            onSelectSource = {},
            onSelectSort = { sort = it },
            onAnimatedOnlyChange = { animated = it },
            onResetFilters = {
                sort = SortMode.NEWEST
                animated = false
            },
            onGridChange = { shown ->
                grid = shown
                owner.synchronize(environment())
            },
            onOpenPost = { level, post, _ -> opened += level.id to post },
            onPickCodex = { _, _ -> },
            onRequestSaveToCodex = {},
            onSaveSearch = { savedSearches += it },
            onToggleLike = {},
            onOpenSourceAccounts = { sourcesOpened++ },
            onGoToSearch = { wentToSearch++ },
        )
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRelatedPostsSupport provides { true }, LocalHiddenTagsOpener provides HiddenTagsOpener { hiddenTagsOpened++ }) {
                    val cards = rememberFeedCardEnvironment(null, emptySet(), emptyMap(), FeedDurations.NONE, {})
                    val config = ForYouScreenConfig(
                        sort, animated, grid, emptySet(), setOf(DECK_SOURCE), FeedDurations.NONE,
                        TriageCardSupport(cards, resolvePostById = { null }, recoverPostMedia = { _, _ -> null }),
                        isSearchSaved = { it in savedSearches },
                    )
                    ForYouDeckScreen(owner, config, testRelatedShelf(), callbacks)
                }
            }
        }
        return owner
    }

    /** Decides the card through the action the deck gives TalkBack, as a swipe would. */
    private fun decide(label: String) {
        val node = compose.onNode(SemanticsMatcher("offers $label") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == label }
        }).fetchSemanticsNode()
        compose.runOnUiThread { node.config[SemanticsActions.CustomActions].first { it.label == label }.action() }
    }

    private fun card(id: String) = compose.onNodeWithTag(searchCardTestTag(PostId(DECK_SOURCE, id)))

    @Test fun `the card says which interest dealt it and Undo brings a skipped card back`() {
        val owner = render()
        compose.onNodeWithTag(FOR_YOU_ORIGIN_TAG).assertTextEquals("fog")
        compose.onNodeWithTag(FOR_YOU_UNDO_TAG).assertIsNotEnabled()

        decide("Skip")
        compose.onNodeWithTag(FOR_YOU_ORIGIN_TAG).assertTextEquals("sea")
        compose.onNodeWithTag(FOR_YOU_UNDO_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(FOR_YOU_ORIGIN_TAG).assertTextEquals("fog")
        compose.runOnIdle { assertEquals(0, owner.levels.value.single().deck.state.value.index) }
    }

    @Test fun `Refresh builds a fresh Mix, and shows on the Mix only`() {
        trails.creator = listOf(testPost(DECK_SOURCE, "t-1"))
        val owner = render()
        val mix = compose.runOnIdle { owner.levels.value.single().id }
        compose.onNodeWithTag(FOR_YOU_REFRESH_TAG).assertContentDescriptionEquals("Refresh").performClick()
        compose.runOnIdle {
            assertEquals(2, engine.builds)
            assertNotEquals(mix, owner.levels.value.single().id)
        }

        compose.onNodeWithTag("${FOR_YOU_FORK_TAG_PREFIX}creator").performClick()
        compose.onNodeWithTag(FOR_YOU_REFRESH_TAG).assertDoesNotExist()
    }

    @Test fun `a fork opens a trail whose crumbs replace the chips, and a crumb returns to the Mix`() {
        trails.creator = listOf(testPost(DECK_SOURCE, "t-1"), testPost(DECK_SOURCE, "t-2"))
        val owner = render()
        compose.onNodeWithTag(FOR_YOU_CHIPS_TAG).assertExists()
        compose.onNodeWithTag("${FOR_YOU_FORK_TAG_PREFIX}creator").assertTextEquals("artist").performClick()

        compose.onNodeWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}0").assertTextEquals("Mix")
        compose.onNodeWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}1").assertTextEquals("artist")
        compose.onNodeWithTag(FOR_YOU_CHIPS_TAG).assertDoesNotExist()
        // Only a tag trail is a search to save, and a trail's cards say nothing about lanes.
        compose.onNodeWithTag(FOR_YOU_SAVE_SEARCH_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FOR_YOU_ORIGIN_TAG).assertDoesNotExist()
        card("t-1").assertExists()

        compose.onNodeWithTag("${FOR_YOU_CRUMB_TAG_PREFIX}0").performClick()
        compose.onNodeWithTag(FOR_YOU_CHIPS_TAG).assertExists()
        compose.runOnIdle { assertEquals(1, owner.levels.value.size) }
    }

    @Test fun `a card a like asked for says so and does not offer its own creator again`() {
        // No similar posts on this source, so the like asks the liked post's creator.
        trails.creator = listOf(lanePost("more", 0, 0))
        render()
        compose.onNodeWithTag("${FOR_YOU_FORK_TAG_PREFIX}creator").assertExists()
        decide("Like")
        repeat(3) { decide("Skip") }

        card("more-0-0").assertExists()
        compose.onNodeWithTag(FOR_YOU_ORIGIN_TAG).assertTextEquals("More by artist")
        compose.onNodeWithTag("${FOR_YOU_FORK_TAG_PREFIX}creator").assertDoesNotExist()
    }

    @Test fun `the fork row scrolls only when its forks overflow it, so a card that fits still swipes from it`() {
        val request = TrailRequest.Similar(testPost(source = DECK_SOURCE, sourcePostId = "1"))
        val similar = ForYouFork("similar", "Similar", request)
        // Each capped well short of the row, but together wider than it whatever the test font's width.
        val long = List(5) { ForYouFork("tag:$it", "long ".repeat(100), request) }
        var forks by mutableStateOf(listOf(similar))
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) { ForYouForkRow(forks) {} } } }

        compose.onNodeWithTag(FOR_YOU_FORK_ROW_TAG).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.ScrollBy))
        forks = listOf(similar) + long
        compose.onNodeWithTag(FOR_YOU_FORK_ROW_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
            .performScrollToKey("tag:4")
        compose.onNodeWithTag("${FOR_YOU_FORK_TAG_PREFIX}tag:4").assertIsDisplayed()
    }

    @Test fun `a tag trail saves its search, and then shows it saved`() {
        trails.tag = listOf(testPost(DECK_SOURCE, "t-1"))
        val owner = render()
        compose.runOnUiThread {
            val card = checkNotNull(owner.levels.value.single().deck.state.value.current)
            owner.fork(TrailRequest.Tag("sky", DECK_SOURCE, context = null, from = card))
        }
        compose.onNodeWithContentDescription("Save search").assertExists()
        compose.onNodeWithTag(FOR_YOU_SAVE_SEARCH_TAG).performClick()
        compose.runOnIdle { assertEquals(listOf(listOf("sky")), savedSearches.map(Query::includeTags)) }
        compose.onNodeWithContentDescription("Saved").assertExists()
        compose.onNodeWithTag(FOR_YOU_SAVE_SEARCH_TAG).assertIsNotEnabled()
    }

    @Test fun `a chip narrows the deck to its interest`() {
        val owner = render()
        compose.onNode(hasText("Mix") and hasClickAction()).assertIsSelected()
        compose.onNode(hasText("sea") and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithTag(FOR_YOU_ORIGIN_TAG).assertTextEquals("sea")
        compose.runOnIdle { assertEquals(laneId("sea"), owner.state.value.only) }
    }

    @Test fun `the toggle shows the same level as a grid, where a tap opens the Viewer on it`() {
        val owner = render()
        card("fog-0-0").performClick()
        compose.onNodeWithContentDescription("Grid").performClick()

        compose.onNodeWithTag(FOR_YOU_GRID_TAG).assertExists()
        compose.onNodeWithTag(FOR_YOU_UNDO_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription("Deck").assertExists()
        card("sea-0-0").performClick()
        compose.runOnIdle {
            val level = owner.levels.value.single().id
            assertEquals(listOf(level to lanePost("fog", 0, 0).id, level to lanePost("sea", 0, 0).id), opened)
        }
    }

    @Test fun `a trail's later page that fails offers Retry above the grid's posts`() {
        val first = listOf(testPost(DECK_SOURCE, "t-1"), testPost(DECK_SOURCE, "t-2"))
        var down = true
        var pages = 0
        trails.creator = first
        trails.pager = TrailPager {
            if (++pages > 1) check(!down) { "down" }
            TrailPage(if (pages == 1) first else first + testPost(DECK_SOURCE, "t-3"), canLoadMore = pages == 1)
        }
        val owner = render()
        compose.onNodeWithTag("${FOR_YOU_FORK_TAG_PREFIX}creator").performClick()
        compose.onNodeWithContentDescription("Grid").performClick()
        compose.runOnIdle { owner.levels.value.last().loadNextPage() }

        compose.onNodeWithTag(FOR_YOU_GRID_TAG).assertExists()
        card("t-1").assertExists()
        down = false
        compose.onNodeWithText("Retry").performClick()
        card("t-3").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun `a Mix with nothing to deal says why`() {
        engine.tags = emptyList()
        render()
        compose.onNodeWithText("No recommendations yet").assertExists()
        compose.onNodeWithTag(FOR_YOU_CHIPS_TAG).assertDoesNotExist()
        compose.onNodeWithText("Go to Search").performClick()
        compose.runOnIdle { assertEquals(1, wentToSearch) }
    }

    @Test fun `a Mix with no sources enabled opens source management`() {
        engine.sources = emptyList()
        render()
        compose.onNodeWithText("No sources enabled").assertExists()
        compose.onNodeWithText("Manage sources").performClick()
        compose.runOnIdle { assertEquals(1, sourcesOpened) }
    }

    @Test fun `a trail keeps its grid place under a deeper trail, which starts at the top`() {
        trails.creator = (1..60).map { testPost(DECK_SOURCE, "t-$it") }
        trails.tag = (1..60).map { testPost(DECK_SOURCE, "g-$it") }
        val owner = render()
        compose.onNodeWithTag("${FOR_YOU_FORK_TAG_PREFIX}creator").performClick()
        compose.onNodeWithContentDescription("Grid").performClick()
        compose.onNodeWithTag(FOR_YOU_GRID_TAG).onChild().performScrollToNode(hasTestTag(searchCardTestTag(PostId(DECK_SOURCE, "t-50"))))
        card("t-1").assertIsNotDisplayed()

        compose.runOnIdle { owner.fork(TrailRequest.Tag("sky", DECK_SOURCE, null, testPost(DECK_SOURCE, "t-50"))) }
        card("g-1").assertIsDisplayed()

        compose.runOnIdle { owner.popTo(1) }
        card("t-50").assertIsDisplayed()
        card("t-1").assertIsNotDisplayed()
    }

    @Test fun `a Mix that could not load offers Retry, which deals it`() {
        engine.failLanes = true
        render()
        engine.failLanes = false
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithTag(FOR_YOU_ORIGIN_TAG).assertTextEquals("fog")
    }

    @Test fun `the profile chip shows only with several profiles and selects another`() {
        settings = AppSettings().copy(
            recommendationProfiles = listOf(RecommendationProfile("main", "Main"), RecommendationProfile("alt", "Night")),
            activeProfileId = "main",
        )
        render()
        compose.onNodeWithText("Main").performClick()
        compose.onNodeWithText("Night").performClick()
        compose.runOnIdle { assertEquals(listOf("alt"), selectedProfiles) }
    }

    @Test fun `a single profile draws no chip`() {
        settings = AppSettings().copy(recommendationProfiles = listOf(RecommendationProfile("main", "Main")), activeProfileId = "main")
        render()
        compose.onNodeWithText("Main").assertDoesNotExist()
    }

    @Test fun `the Filters button shows when a filter is on, and Reset restores the sort and the media choice`() {
        render()
        compose.onNodeWithTag(FOR_YOU_FILTERS_ACTIVE_TAG, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(FOR_YOU_FILTERS_TAG).performClick()
        compose.onNodeWithText("Animated only").performClick()
        compose.onNodeWithText(sortModeLabel(SortMode.POPULAR)).performClick()
        compose.runOnIdle { assertEquals(SortMode.POPULAR to true, sort to animated) }
        compose.onNodeWithTag(FOR_YOU_FILTERS_ACTIVE_TAG, useUnmergedTree = true).assertExists()

        compose.onNodeWithText("Reset").performClick()
        compose.runOnIdle { assertEquals(SortMode.NEWEST to false, sort to animated) }
        compose.onNodeWithTag(FOR_YOU_FILTERS_ACTIVE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `Hidden tags is a row that leaves the sheet for the profile's hidden tags`() {
        render()
        compose.onNodeWithTag(FOR_YOU_FILTERS_TAG).performClick()
        // A row, not a choice: it has no selected state.
        compose.onNode(hasText("Hidden tags") and hasClickAction() and SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)).performClick()
        compose.runOnIdle { assertEquals(1, hiddenTagsOpened) }
        compose.onNodeWithText("Animated only").assertDoesNotExist()
    }
}
