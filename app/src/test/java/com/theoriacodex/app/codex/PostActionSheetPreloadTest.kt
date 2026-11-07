package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.data.repository.InMemoryCodexRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostActionSheetPreloadTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `opening hold menu starts matching before save is selected`() {
        val repository = InMemoryCodexRepository()
        runBlocking { repository.ensureCodex("target", "Target") }
        val post = testPost()
        val query = emptySearchQuery()
        var lookups = 0
        var saves = 0
        lateinit var loader: CodexSaveRelevanceLoader
        compose.setContent {
            val scope = rememberCoroutineScope()
            loader = androidx.compose.runtime.remember {
                CodexSaveRelevanceLoader(repository, scope, Dispatchers.Unconfined) { ids ->
                    assertEquals(setOf("target"), ids)
                    lookups++
                    flowOf(mapOf("target" to listOf(testPost(sourcePostId = "saved"))))
                }
            }
            CompositionLocalProvider(LocalCodexSaveRelevanceLoader provides loader) {
                MaterialTheme {
                    PostActionSheet(post = post, onDismiss = {}, onSaveToDevice = {},
                        onSaveToCodex = { saves++ }, saveToCodexQuery = query,
                        tagContent = {})
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, lookups)
            assertEquals(0, saves)
            assertEquals(mapOf("target" to 1.0), loader.observe(listOf(post), query).value)
        }
        compose.onNodeWithText("Save to Codex").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }
}
