package com.theoriacodex.app.journeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.statistics.statisticsPostKey
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import org.junit.Test

class StatsTopPostJourneyTest : AppJourneyFixture() {
    @Test
    fun topPostsOpenInTheViewerWhetherOrNotTheyAreStillSaved() {
        val gelbooru = posts.getValue(SourceKey.GELBOORU)
        val saved = gelbooru[3]
        val noLongerSaved = gelbooru[4]
        io {
            val codex = container.content.ensureCodex(profileScopedCodexId("profile-main", "journey"), "Journey collection")
            container.content.addItems(codex.codexId, listOf(saved))
            repeat(2) { container.data.statisticsRepository.recordCodexPostOpen(codex.codexId, statisticsPostKey(saved.id), "Gelbooru journey-3") }
            container.data.statisticsRepository.recordCodexPostOpen(codex.codexId, statisticsPostKey(noLongerSaved.id), "Gelbooru journey-4")
        }
        launch()
        openSettings()
        activate(compose.onNodeWithText("Your stats"))
        activate(compose.onNodeWithText("Codices"))
        activate(compose.onNodeWithText("Journey collection"))

        openFromTopPosts("Gelbooru journey-3", saved)
        openFromTopPosts("Gelbooru journey-4", noLongerSaved)
    }

    @Test
    fun theSharePreviewLetsPostsBeInspectedAndLeftOut() {
        val post = posts.getValue(SourceKey.GELBOORU)[5]
        io {
            val codex = container.content.ensureCodex(profileScopedCodexId("profile-main", "journey"), "Landscapes")
            container.content.addItems(codex.codexId, listOf(post))
            container.data.statisticsRepository.recordCodexPostOpen(codex.codexId, statisticsPostKey(post.id), "Gelbooru journey-5")
        }
        launch()
        openSettings()
        activate(compose.onNodeWithText("Share my stats"))
        waitFor("share sheet shows 1 open") { compose.onNodeWithText("1 opens", substring = true).isDisplayed() }
        compose.onNodeWithText("Share file").assertIsEnabled()

        activate(compose.onNodeWithText("Landscapes").performScrollTo())
        val thumbnail = compose.onNodeWithTag("stats-post-thumbnail:${statisticsPostKey(post.id)}", useUnmergedTree = true)
        waitFor("top post thumbnail is displayed") { thumbnail.isDisplayed() }
        openFromTopPosts("Gelbooru journey-5", post, returnTo = "Tap a post to look at it. Uncheck it to leave it out.")
        activate(compose.onNodeWithContentDescription("Include Gelbooru journey-5"))
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        waitFor("stats show 0 opens after leaving the post out") { compose.onAllNodesWithText("0 opens", substring = true).fetchSemanticsNodes().isNotEmpty() }
        activate(compose.onNodeWithContentDescription("Include Landscapes").performScrollTo())
        compose.onNodeWithText("Left out").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Share file").assertIsEnabled()
        activate(compose.onNodeWithContentDescription("Include overall stats").performScrollTo())
        val shareFile = compose.onNodeWithText("Share file")
        waitFor("Share file is disabled once everything is left out") { !isEnabled().matches(shareFile.fetchSemanticsNode()) }
    }

    private fun openFromTopPosts(label: String, post: Post, returnTo: String = "Top posts") {
        activate(compose.onNodeWithText(label))
        val viewer = compose.onNodeWithContentDescription("Open post ${post.id.sourcePostId}", useUnmergedTree = true)
        waitFor("Viewer shows post ${post.id.sourcePostId}'s Open post control or its navigation strip") { viewer.isDisplayed() || compose.onNodeWithTag("viewer-post-navigation-strip").isDisplayed() }
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("$returnTo is displayed after Back from the Viewer") { compose.onNodeWithText(returnTo).isDisplayed() }
    }
}
