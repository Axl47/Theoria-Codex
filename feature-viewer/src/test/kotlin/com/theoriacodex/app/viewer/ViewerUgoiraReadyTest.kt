package com.theoriacodex.app.viewer

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.testing.waitUntilWithoutIdling
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
import com.theoriacodex.domain.model.VideoQuality
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerUgoiraReadyTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `an ugoira reports ready once its frames are available`() {
        var ready = 0
        showUgoira(active = mutableStateOf(true)) { ready += 1 }
        compose.waitUntilWithoutIdling(timeoutMillis = 10_000L) { ready > 0 }
    }

    @Test fun `an ugoira that loaded while preloading reports ready again once it becomes active`() {
        var ready = 0
        val active = mutableStateOf(false)
        showUgoira(active) { ready += 1 }
        compose.waitUntilWithoutIdling(timeoutMillis = 10_000L) { ready > 0 }
        val whilePreloading = ready

        active.value = true
        compose.waitForIdle()
        assertTrue(ready > whilePreloading)
    }

    // Paused: a playing, looping animation keeps Compose from ever going idle, so setContent could time out.
    private fun showUgoira(active: State<Boolean>, onReady: () -> Unit) {
        val media = ImageRef(url = null, localPath = null, mime = PIXIV_UGOIRA_MIME)
        val post = testPost(sourcePostId = "77").copy(full = media, media = listOf(media))
        val events = ViewerRendererEvents(
            onPlaybackCompleted = {}, onError = {}, onReady = onReady, onDurationKnown = {},
            onTogglePlayback = {}, onRestartPlayback = {}, onPlaybackIntentChanged = {}, onTimelineInteractionActiveChanged = {},
        )
        val client = client()
        compose.setContent {
            ViewerMediaContent(
                post, media, ViewerMediaKey(post.id, 0), ViewerMediaKind.UGOIRA,
                ViewerRendererControls(
                    isActive = active.value, isPlaying = false, showControls = true, loopPlayback = true,
                    playbackRate = 1f, restartRequest = 0L, loadGeneration = 0L, isPreloading = !active.value,
                ),
                events, ViewerVideoOptions(VideoQuality.AUTO, null, null, null, false), ViewerVideoPositions(),
                ViewerImageSession(), client, Modifier, onRequestMediaRecovery = null,
            )
        }
    }

    private fun client() = PixivUgoiraClient(
        credentialsProvider = Credentials,
        httpClient = NoHttp,
        archiveDirectory = temporaryFolder.newFolder(),
        metadataFetcher = { _, _ ->
            TextResponse(
                200,
                """{"ugoira_metadata":{"zip_urls":{"medium":"https://example.test/a.zip"},"frames":[{"file":"0.png","delay":50}]}}""",
            )
        },
        zipDownloader = { _, _, destination -> writeZip(destination); BinaryResponse(200) },
    )

    private fun writeZip(destination: File) {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            zip.putNextEntry(ZipEntry("0.png")); zip.write(bytes); zip.closeEntry()
        }
    }

    private object Credentials : SourceCredentialsProvider {
        override suspend fun getPixivTokens() = PixivAuthTokens("access", "refresh", Long.MAX_VALUE)
        override suspend fun savePixivTokens(tokens: PixivAuthTokens) = Unit
        override suspend fun clearPixivTokens() = Unit
    }

    private object NoHttp : SourceHttpClient {
        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("unused")

        override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("unused")
    }
}
