package com.theoriacodex.app.viewer

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerMediaState
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerUgoiraPrefetchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val archiveDownloads = AtomicInteger()

    @Test
    fun `an unmetered ugoira neighbour has its archive cached before it is opened`() = runTest {
        setMetered(false)
        val client = client()

        val result = prefetchViewerMedia(context, ugoiraMedia(), VideoQuality.AUTO, ugoiraClient = client)

        assertEquals(ViewerPrefetchOutcome.WARMED, result.outcome)
        assertEquals(1, archiveDownloads.get())
        // Opening it later finds the archive on disk instead of downloading again.
        client.warmArchive("77")
        assertEquals(1, archiveDownloads.get())
    }

    @Test
    fun `a metered network leaves the archive alone`() = runTest {
        setMetered(true)

        // Without a still location the metered fallback has nothing to warm, which keeps the test off the network.
        val result = prefetchViewerMedia(context, ugoiraMedia(), VideoQuality.AUTO, ugoiraClient = client())

        assertEquals(ViewerPrefetchOutcome.SKIPPED, result.outcome)
        assertEquals(0, archiveDownloads.get())
    }

    private fun setMetered(metered: Boolean) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val type = if (metered) ConnectivityManager.TYPE_MOBILE else ConnectivityManager.TYPE_WIFI
        shadowOf(manager).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED),
        )
    }

    private fun ugoiraMedia() = ViewerMediaState(
        key = ViewerMediaKey(PostId(SourceKey.PIXIV, "77"), 0),
        ref = ImageRef(url = null, localPath = null, mime = PIXIV_UGOIRA_MIME),
        kind = ViewerMediaKind.UGOIRA,
        displayLocation = null,
    )

    private fun client() = PixivUgoiraClient(
        credentialsProvider = Credentials,
        httpClient = NoHttp,
        archiveDirectory = temporaryFolder.newFolder(),
        metadataFetcher = { _, _ ->
            TextResponse(
                200,
                """{"ugoira_metadata":{"zip_urls":{"medium":"https://example.test/a.zip"},"frames":[{"file":"0001.jpg","delay":50}]}}""",
            )
        },
        zipDownloader = { _, _, destination ->
            archiveDownloads.incrementAndGet()
            writeZip(destination)
            BinaryResponse(200)
        },
    )

    private fun writeZip(destination: File) {
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            zip.putNextEntry(ZipEntry("0001.jpg"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
    }

    private object Credentials : SourceCredentialsProvider {
        override suspend fun getPixivTokens() = PixivAuthTokens("access", "refresh", Long.MAX_VALUE)
        override suspend fun savePixivTokens(tokens: PixivAuthTokens) = Unit
        override suspend fun clearPixivTokens() = Unit
        override suspend fun getGelbooruCredentials(): GelbooruCredentials? = null
        override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) = Unit
        override suspend fun clearGelbooruCredentials() = Unit
        override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? = null
        override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) = Unit
        override suspend fun clearRule34XxxCredentials() = Unit
    }

    private object NoHttp : SourceHttpClient {
        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("unused")

        override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
            error("unused")
    }
}
