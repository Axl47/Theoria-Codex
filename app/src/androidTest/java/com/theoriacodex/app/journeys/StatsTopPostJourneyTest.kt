package com.theoriacodex.app.journeys

import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
        tab("Settings")
        activate(compose.onNodeWithText("Your stats"))
        activate(compose.onNodeWithText("Codices"))
        activate(compose.onNodeWithText("Journey collection"))

        openFromTopPosts("Gelbooru journey-3", saved)
        openFromTopPosts("Gelbooru journey-4", noLongerSaved)
    }

    private fun openFromTopPosts(label: String, post: Post) {
        activate(compose.onNodeWithText(label))
        val viewer = compose.onNodeWithContentDescription("Open post ${post.id.sourcePostId}", useUnmergedTree = true)
        compose.waitUntil(10_000) { viewer.isDisplayed() || compose.onNodeWithTag("viewer-post-navigation-strip").isDisplayed() }
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onNodeWithText("Top posts").isDisplayed() }
    }
}
