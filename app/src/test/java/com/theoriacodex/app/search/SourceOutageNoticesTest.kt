package com.theoriacodex.app.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.source.LocalSourceHealth
import com.theoriacodex.app.source.SourceHealth
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.sources.http.HostOutageKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourceOutageNoticesTest {
    @get:Rule val compose = createComposeRule()

    private val maintenance = SourceOutageNotice(HostOutageKind.MAINTENANCE, listOf(SourceKey.DANBOORU))
    private val challenge = SourceOutageNotice(HostOutageKind.CHALLENGE, listOf(SourceKey.IWARA))

    private fun show(notices: List<SourceOutageNotice>, verify: (SourceKey) -> Unit = {}) {
        compose.setContent {
            var dismissed by remember { mutableStateOf(emptySet<SourceKey>()) }
            val health = SourceHealth(verify = verify, dismissedNotices = dismissed, dismissNotices = { dismissed = dismissed + it })
            CompositionLocalProvider(LocalSourceHealth provides health) { SourceOutageNotices(notices) }
        }
    }

    @Test
    fun `maintenance can only be dismissed, and stays gone`() {
        show(listOf(maintenance))
        compose.onNodeWithText("Danbooru: Down for maintenance. Try again later.").assertExists()
        compose.onNodeWithText("Verify in browser").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()

        compose.onNodeWithText("Dismiss").performClick()

        compose.onNodeWithText("Danbooru: Down for maintenance. Try again later.").assertDoesNotExist()
    }

    @Test
    fun `a challenge offers verification for its source alongside Dismiss`() {
        val verified = mutableListOf<SourceKey>()
        show(listOf(challenge), verified::add)
        compose.onNodeWithText("Dismiss").assertExists()

        compose.onNodeWithText("Verify in browser").performClick()

        assertEquals(listOf(SourceKey.IWARA), verified)
    }

    @Test
    fun `a single-source failure behind a challenge verifies instead of retrying`() {
        val verified = mutableListOf<SourceKey>()
        val statuses = listOf(SourceRunStatus(SourceKey.IWARA, SourceRunState.FAILED))
        compose.setContent {
            val health = SourceHealth(issues = mapOf(SourceKey.IWARA to HostOutageKind.CHALLENGE), verify = verified::add)
            CompositionLocalProvider(LocalSourceHealth provides health) { SourceFailureBlock("Iwara failed", statuses) {} }
        }
        compose.onNodeWithText("Retry").assertDoesNotExist()

        compose.onNodeWithText("Verify in browser").performClick()

        assertEquals(listOf(SourceKey.IWARA), verified)
    }
}
