package com.theoriacodex.app.journeys

import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.library.LIBRARY_SETTINGS_TAG
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.domain.model.SourceKey
import org.junit.Test

class HeaderHidesOnScrollJourneyTest : AppJourneyFixture() {
    @Test
    fun theLibraryHeaderLeavesOnScrollDownAndReturnsOnScrollUpWhileTabsStay_adr_0218() {
        io {
            val post = posts.getValue(SourceKey.PIXIV).first()
            repeat(CODICES) { index ->
                val codex = container.content.ensureCodex(profileScopedCodexId("profile-main", "scroll-$index"), "Scroll $index")
                container.content.addItems(codex.codexId, listOf(post))
            }
        }
        launch()
        library(LibrarySection.Codices)
        val settings = compose.onNodeWithTag(LIBRARY_SETTINGS_TAG)
        waitFor("Library settings is displayed") { settings.isDisplayed() }

        compose.onRoot().performTouchInput { swipeUp(startY = centerY + height / 4f, endY = centerY - height / 4f) }
        waitFor("Library header hid on scroll-down") { !settings.isDisplayed() }
        waitFor("bottom navigation stayed") { compose.onNodeWithContentDescription("Search").isDisplayed() }

        compose.onRoot().performTouchInput { swipeDown(startY = centerY, endY = centerY + height / 10f) }
        waitFor("Library header returned on scroll-up") { settings.isDisplayed() }
    }

    private companion object {
        const val CODICES = 30
    }
}
