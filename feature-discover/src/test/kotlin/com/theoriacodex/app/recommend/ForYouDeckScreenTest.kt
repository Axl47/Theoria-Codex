package com.theoriacodex.app.recommend

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.related.testRelatedShelf
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.triage.TriageCardSupport
import com.theoriacodex.app.ui.components.FeedDurations
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
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

    /** Each Viewer launch, as the level's id and the post. */
    private val opened = mutableListOf<Pair<String, PostId>>()
    private val savedSearches = mutableListOf<Query>()
    private val selectedProfiles = mutableListOf<String>()
    private val animatedOnly = mutableListOf<Boolean>()

    @After fun tearDown() = writeScope.cancel()

    private fun environment() = deckEnvironment(settings, grid, creatorBrowsingSources = setOf(DECK_SOURCE))

    private fun render(): ForYouDeckViewModel {
        val owner = ForYouDeckViewModel(engine, deckWiring(writeScope, trails)).also { it.synchronize(environment()) }
        val callbacks = ForYouDeckCallbacks(
            onSelectProfile = { selectedProfiles += it },
            onSelectSource = {},
            onSelectSort = {},
            onAnimatedOnlyChange = { animatedOnly += it },
            onGridChange = { shown ->
                grid = shown
                owner.synchronize(environment())
            },
            onOpenPost = { level, post, _ -> opened += level.id to post },
            onPickCodex = { _, _ -> },
            onRequestSaveToCodex = {},
            onSaveToDevice = {},
            onSaveSearch = { savedSearches += it },
            onToggleLike = {},
            onOpenSourceAccounts = {},
            onGoToSearch = {},
        )
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRelatedPostsSupport provides { true }) {
                    val cards = rememberFeedCardEnvironment(null, emptySet(), emptyMap(), FeedDurations.NONE, {})
                    val config = ForYouScreenConfig(
                        SortMode.NEWEST, animatedOnly = false, grid, emptySet(), setOf(DECK_SOURCE), FeedDurations.NONE,
                        TriageCardSupport(cards, resolvePostById = { null }, recoverPostMedia = { _, _ -> null }),
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

    @Test fun `a fork opens a trail whose crumbs replace the chips, and a crumb returns to the Mix`() {
        trails.creator = listOf(testPost(DECK_SOURCE, "t-1"), testPost(DECK_SOURCE, "t-2"))
        val owner = render()
        compose.onNodeWithTag(FOR_YOU_CHIPS_TAG).assertExists()
        compose.onNodeWithTag("${FOR_YOU_FORK_TAG_PREFIX}creator").assertTextEquals("artist").performClick()

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

    @Test fun `a tag trail saves its search`() {
        trails.tag = listOf(testPost(DECK_SOURCE, "t-1"))
        val owner = render()
        compose.runOnUiThread {
            val card = checkNotNull(owner.levels.value.single().deck.state.value.current)
            owner.fork(TrailRequest.Tag("sky", DECK_SOURCE, context = null, from = card))
        }
        compose.onNodeWithTag(FOR_YOU_SAVE_SEARCH_TAG).performClick()
        compose.runOnIdle { assertEquals(listOf(listOf("sky")), savedSearches.map(Query::includeTags)) }
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

    @Test fun `a Mix with nothing to deal says why`() {
        engine.tags = emptyList()
        render()
        compose.onNodeWithText("No recommendations yet").assertExists()
        compose.onNodeWithTag(FOR_YOU_CHIPS_TAG).assertDoesNotExist()
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

    @Test fun `a single profile draws no chip and filters hold the media choice`() {
        settings = AppSettings().copy(recommendationProfiles = listOf(RecommendationProfile("main", "Main")), activeProfileId = "main")
        render()
        compose.onNodeWithText("Main").assertDoesNotExist()
        compose.onNodeWithTag(FOR_YOU_FILTERS_TAG).performClick()
        compose.onNodeWithText("Animated only").performClick()
        compose.runOnIdle { assertEquals(listOf(true), animatedOnly) }
    }
}
