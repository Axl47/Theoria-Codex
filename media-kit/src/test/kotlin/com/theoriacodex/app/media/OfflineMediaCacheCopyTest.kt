package com.theoriacodex.app.media

import android.app.Application
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OfflineMediaCacheCopyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a copy taken from the media caches is checked like a download (ADR 0110)`() = runTest {
        var copiedFromCache = false
        val acquisition = OfflineMediaAcquisition(
            context = RuntimeEnvironment.getApplication(),
            registry = NoSources,
            settings = InMemorySettingsRepository(),
            ugoira = PixivUgoiraClient(NoCredentials, NoHttp, archiveDirectory = folder.newFolder()),
            copyCachedVideo = { _, _, destination ->
                copiedFromCache = true
                destination.writeText("<html>error</html>")
                true
            },
        )
        val media = ImageRef(url = "http://127.0.0.1:9/page.png", localPath = null, mime = "image/png")
        val post = Post(
            id = PostId(SourceKey.GELBOORU, "1"),
            preview = media,
            full = media,
            media = listOf(media),
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )

        val result = runCatching { acquisition.acquire(post, media, folder.newFile()) }

        assertTrue(copiedFromCache)
        assertTrue("An error page from the cache must not count as the saved file", result.isFailure)
    }

    private object NoSources : SourceAdapterRegistry {
        override fun availableSources(): Set<SourceKey> = emptySet()
        override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = null
        override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = error("Search is not used")
    }

    private object NoCredentials : SourceCredentialsProvider {
        override suspend fun getPixivTokens(): PixivAuthTokens? = null
        override suspend fun savePixivTokens(tokens: PixivAuthTokens) = Unit
        override suspend fun clearPixivTokens() = Unit
    }

    private object NoHttp : SourceHttpClient {
        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("HTTP is not used")
        override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("HTTP is not used")
    }
}
