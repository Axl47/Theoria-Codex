package com.theoriacodex.mihonhost

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.series.Series
import java.io.File
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs one real extension APK end to end on a device, in this module's own test package: load it, list series,
 * open one's first chapter, and fetch a page image through the extension's client. It reports counts, sizes and timings
 * as text only, and never shows or saves provider media.
 *
 * Skipped unless an APK is named: push it to this test package's external files directory, then run with
 * `-e extensionApk <file name>` (see this module's README).
 */
@RunWith(AndroidJUnit4::class)
class ExtensionHarnessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun extensionBrowsesEndToEnd() = runBlocking {
        val apkName = InstrumentationRegistry.getArguments().getString("extensionApk")
        assumeNotNull(apkName)
        val context = instrumentation.targetContext
        MihonHost.install(context.applicationContext as Application, versionName = "harness", versionCode = 1)

        val extension = timed("load") { ExtensionLoader(context).load(File(context.getExternalFilesDir(null), apkName!!)) }
        val source = extension.sources.firstOrNull { it.lang == "en" } ?: extension.sources.first()
        report("extension", "${extension.name} ${extension.versionName}, ${extension.sources.size} sources, signer ${extension.signatureSha256}")
        report("source", "${source.name} lang=${source.lang} supportsLatest=${source.supportsLatest}")

        val images = ExtensionImages()
        val series = MihonSeriesSource(source, images) { report("source", it) }
        val listing = timed("popular") { series.popular(1) }
        report("popular", "${listing.series.size} series, covers=${listing.series.count { it.coverUrl != null }}, more=${listing.hasNextPage}")
        assertTrue("The popular listing is empty", listing.series.isNotEmpty())

        val extensionClient = OkHttpClient.Builder().addInterceptor(images).build()
        val coverUrl = checkNotNull(listing.series.firstNotNullOfOrNull { it.coverUrl }) { "No series has a cover" }
        val cover = timed("cover") { fetch(extensionClient, coverUrl) }
        report("cover", "through extension: $cover")
        report("cover", "plain request: ${runCatching { fetch(OkHttpClient(), coverUrl) }.getOrElse { "failed: $it" }}")
        assertTrue("The cover did not arrive as an image: $cover", cover.startsWith("image "))
        // A feed asks for every cover on screen at once, which some sites answer differently from one request.
        val together = timed("covers together") {
            coroutineScope {
                listing.series.mapNotNull { it.coverUrl }.map { url ->
                    async(Dispatchers.IO) { runCatching { fetch(extensionClient, url) }.getOrElse { "failed: ${it.javaClass.simpleName}" } }
                }.awaitAll()
            }
        }
        report("covers together", together.groupingBy { it.substringBeforeLast(',') }.eachCount().toString())

        val pages = timed("open") { firstReadable(series, listing.series) }
        report("open", "pages=${pages.size}")

        val pageUrl = checkNotNull(pages.first().url)
        val throughExtension = timed("image") { fetch(extensionClient, pageUrl) }
        report("image", "through extension: $throughExtension")
        report("image", "plain request: ${runCatching { fetch(OkHttpClient(), pageUrl) }.getOrElse { "failed: $it" }}")
        assertTrue("The page did not arrive as an image: $throughExtension", throughExtension.startsWith("image "))
    }

    /**
     * The pages of the first chapter of the first listed series that has one. Some series list no chapter in the
     * source's language, so listed series are opened until one does.
     */
    private suspend fun firstReadable(source: MihonSeriesSource, listed: List<Series>): List<ImageRef> =
        listed.take(MAX_SERIES_TRIED).firstNotNullOfOrNull { series ->
            val first = source.entries(source.details(series)).lastOrNull()
            first?.let { source.pages(it) }?.takeIf { it.isNotEmpty() }
        } ?: error("None of the first $MAX_SERIES_TRIED series had a readable chapter")

    /** Describes a response by its leading bytes, so nothing depends on the server's content type. */
    private fun fetch(client: OkHttpClient, url: String): String = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        val bytes = response.body.bytes()
        val kind = IMAGE_SIGNATURES.entries.firstOrNull { (signature, _) -> bytes.startsWith(signature) }?.value
        "${if (kind != null) "image $kind" else "not an image"}, HTTP ${response.code}, ${bytes.size} bytes"
    }

    private fun ByteArray.startsWith(signature: ByteArray): Boolean =
        size >= signature.size && signature.indices.all { this[it] == signature[it] }

    private inline fun <T> timed(step: String, block: () -> T): T {
        val requests = MihonHost.requestCount.get()
        val started = TimeSource.Monotonic.markNow()
        return block().also {
            report("timing", "$step: ${started.elapsedNow().inWholeMilliseconds} ms, ${MihonHost.requestCount.get() - requests} requests")
        }
    }

    /** Shown by `am instrument -r` as it happens, and in logcat under [TAG]. */
    private fun report(key: String, message: String) {
        Log.i(TAG, "$key: $message")
        instrumentation.sendStatus(0, Bundle().apply { putString("harness", "$key: $message") })
    }

    private companion object {
        const val TAG = "MihonHarness"
        const val MAX_SERIES_TRIED = 5
        val IMAGE_SIGNATURES = mapOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) to "jpeg",
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) to "png",
            "GIF8".toByteArray() to "gif",
            "RIFF".toByteArray() to "webp",
        )
    }
}
