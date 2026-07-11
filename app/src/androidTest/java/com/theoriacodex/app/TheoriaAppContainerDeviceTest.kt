package com.theoriacodex.app

import android.content.res.Configuration
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class TheoriaAppContainerDeviceTest {
    @Test
    fun applicationOwnsOneStableContainerAndFeatureGraphAcrossConfigurationNotification() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TheoriaApplication

        val first = runBlocking { application.awaitAppContainer() }
        val firstSearch = first.features.search
        val firstRegistry = first.sources.registry

        application.onConfigurationChanged(Configuration(application.resources.configuration))

        val second = runBlocking { application.awaitAppContainer() }

        assertSame(first, second)
        assertSame(first.data, second.data)
        assertSame(first.sources, second.sources)
        assertSame(first.updates, second.updates)
        assertSame(first.features, second.features)
        assertSame(first.workflows, second.workflows)
        assertSame(firstRegistry, second.sources.registry)
        assertSame(firstSearch, second.features.search)
    }

    @Test
    fun activityRecreationRetainsThePendingViewerNavigationPayload() {
        val retainedSession = ViewerSession(
            posts = listOf(
                Post(
                    id = PostId(SourceKey.PIXIV, "retained"),
                    preview = ImageRef(
                        url = "https://example.test/retained.jpg",
                        localPath = null,
                        mime = "image/jpeg",
                    ),
                    full = null,
                    pageUrl = null,
                    width = null,
                    height = null,
                    canonicalTags = emptyList(),
                    rawTags = emptyList(),
                    authorName = null,
                    createdAtEpochMs = null,
                ),
            ),
            context = ViewerLaunchContext(
                queryHash = "search:retained",
                startIndex = 0,
                streamSource = ViewerStreamSource.SEARCH,
                scrollOffsetHint = 0,
            ),
            liveSearchBinding = true,
            searchVisibilityFilters = SearchVisibilityFilters(),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[ViewerSessionRetentionViewModel::class.java]
                    .retain(retainedSession)
            }

            scenario.recreate()

            scenario.onActivity { recreatedActivity ->
                val retained = ViewModelProvider(recreatedActivity)[ViewerSessionRetentionViewModel::class.java]
                    .session
                    .value
                assertEquals(retainedSession, retained)
            }
        }
    }
}
