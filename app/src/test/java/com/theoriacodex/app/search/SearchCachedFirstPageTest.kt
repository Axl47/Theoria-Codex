package com.theoriacodex.app.search

import com.theoriacodex.app.fixtures.JourneySourceRegistry
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.SearchFirstPageStore
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchCachedFirstPageTest {
    @get:Rule val folder = TemporaryFolder()

    private fun post(source: SourceKey) = Post(
        id = PostId(source, "1"),
        preview = ImageRef(url = "https://example.test/1.jpg", localPath = null, mime = "image/jpeg"),
        full = null,
        pageUrl = null,
        width = 100,
        height = 100,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )

    @Test
    fun `sources turned off or removed since the last run are left out`() = runTest {
        val store = SearchFirstPageStore(File(folder.root, "first-page.json"))
        store.save("key", listOf(SourceKey.GELBOORU, SourceKey.PIXIV, SourceKey.DANBOORU).map(::post))
        val available = mapOf(SourceKey.GELBOORU to emptyList<Post>(), SourceKey.PIXIV to emptyList())
        val coordinator = testSearchCoordinator(JourneySourceRegistry(available, 20), firstPageStore = store)
        val defaults = AppSettings()
        coordinator.updateEnvironment(defaults.copy(runtime = defaults.runtime.copy(enabledSources = setOf(SourceKey.GELBOORU))))

        val posts = coordinator.cachedFirstPage("key", SearchSourceScope.GlobalUnified)

        assertEquals(listOf(SourceKey.GELBOORU), posts.map { it.id.source })
    }
}
