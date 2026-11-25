package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.rules.TemporaryFolder

internal fun TemporaryFolder.fixtureClient(
    directory: File = newFolder(),
    timeoutMs: Long = 30_000L,
    decode: UgoiraFrameDecoder = ::decodeUgoiraFrames,
    credentials: SourceCredentialsProvider = FixedCredentialsProvider,
    httpClient: SourceHttpClient = UnusedHttpClient,
    frames: String = """[{"file":"0001.png","delay":50}]""",
    download: (String, String, File) -> BinaryResponse = { _, _, file ->
        writeFrameZip(file)
        BinaryResponse(200)
    },
) = PixivUgoiraClient(
    credentialsProvider = credentials,
    httpClient = httpClient,
    archiveDirectory = directory,
    metadataFetcher = { _, _ -> TextResponse(200,
        """{"ugoira_metadata":{"zip_urls":{"medium":"https://example.test/a.zip"},"frames":$frames}}""",
    ) },
    zipDownloader = download,
    decode = decode,
    operationTimeoutMs = timeoutMs,
)

internal fun writeFrameZip(destination: File) {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    bitmap.recycle()
    ZipOutputStream(FileOutputStream(destination)).use { zip ->
        zip.putNextEntry(ZipEntry("0001.png"))
        zip.write(bytes)
        zip.closeEntry()
    }
}

internal class CancellingCredentialsProvider(
    private val cancellation: CancellationException,
) : SourceCredentialsProvider {
    override suspend fun getPixivTokens(): PixivAuthTokens? = throw cancellation
    override suspend fun savePixivTokens(tokens: PixivAuthTokens) = Unit
    override suspend fun clearPixivTokens() = Unit
}

internal object FixedCredentialsProvider : SourceCredentialsProvider {
    fun getTokens() = PixivAuthTokens(accessToken = "access", refreshToken = "refresh", expiresAtEpochMs = Long.MAX_VALUE)
    override suspend fun getPixivTokens(): PixivAuthTokens = getTokens()
    override suspend fun savePixivTokens(tokens: PixivAuthTokens) = Unit
    override suspend fun clearPixivTokens() = Unit
}

internal object UnusedHttpClient : SourceHttpClient {
    override suspend fun get(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse = error("HTTP should not be reached after credential cancellation")

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse = error("HTTP should not be reached after credential cancellation")
}
