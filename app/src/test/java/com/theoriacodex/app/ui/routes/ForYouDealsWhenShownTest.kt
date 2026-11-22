package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.TheoriaAppContent
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The tabs sit side by side, so going from Search to Library scrolls across For You. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForYouDealsWhenShownTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `a tab change that passes over For You asks the sources for nothing, and opening For You deals`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val show = mutableStateOf(true)
        var owners: ViewModelStoreOwner? = null
        val media = ImageRef(null, "/nonexistent/post.jpg", "image/jpeg")
        val posts = List(8) { index ->
            testPost(SourceKey.PIXIV, "post-$index").copy(preview = media, full = media, width = 160, height = 160, canonicalTags = listOf("sea", "fog"))
        }
        val graph = runBlocking(Dispatchers.IO) {
            JourneyAppContainer(ApplicationProvider.getApplicationContext(), temporary.newFolder(), mapOf(SourceKey.PIXIV to posts), scope)
                .also { it.awaitReady() }
        }
        try {
            compose.setContent {
                val owner = LocalViewModelStoreOwner.current
                SideEffect { owners = owner }
                if (show.value) TheoriaAppContent(graph, viewModel<ViewerSessionRetentionViewModel>(), viewModel<AppShellViewModel>())
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Library").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Library").performClick()
            compose.waitForIdle()
            assertTrue("For You dealt while passed over: ${graph.registry.requests}", graph.registry.requests.isEmpty())

            compose.onNodeWithContentDescription("For You").performClick()
            compose.waitForIdle()
            compose.waitUntil(10_000) { graph.registry.requests.isNotEmpty() }
        } finally {
            compose.runOnIdle { show.value = false; owners?.viewModelStore?.clear() }
            runBlocking(Dispatchers.IO) { graph.shutdown() }
            scope.cancel()
        }
    }
}
