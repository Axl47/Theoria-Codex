package com.theoriacodex.mihonhost

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import eu.kanade.tachiyomi.source.Source
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
 * open one as a post, and fetch a page image through the extension's client. It reports counts, sizes and timings
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
        val adapter = MihonSourceAdapter(MihonSourceAdapter.sourceKey(source), source, images) { report("adapter", it) }
        val listing = timed("popular") { adapter.search(query(source, SortMode.POPULAR), pageToken = null) }
        report("popular", "${listing.items.size} series, covers=${listing.items.count { it.preview.url != null }}, next=${listing.nextPageToken}")
        assertTrue("The popular listing is empty", listing.items.isNotEmpty())

        val extensionClient = OkHttpClient.Builder().addInterceptor(images).build()
        val coverUrl = checkNotNull(listing.items.firstNotNullOfOrNull { it.preview.url }) { "No series has a cover" }
        val cover = timed("cover") { fetch(extensionClient, coverUrl) }
        report("cover", "through extension: $cover")
        report("cover", "plain request: ${runCatching { fetch(OkHttpClient(), coverUrl) }.getOrElse { "failed: $it" }}")
        assertTrue("The cover did not arrive as an image: $cover", cover.startsWith("image "))
        // A feed asks for every cover on screen at once, which some sites answer differently from one request.
        val together = timed("covers together") {
            coroutineScope {
                listing.items.mapNotNull { it.preview.url }.map { url ->
                    async(Dispatchers.IO) { runCatching { fetch(extensionClient, url) }.getOrElse { "failed: ${it.javaClass.simpleName}" } }
                }.awaitAll()
            }
        }
        report("covers together", together.groupingBy { it.substringBeforeLast(',') }.eachCount().toString())

        val opened = timed("open") { firstReadable(adapter, listing.items) }
        report("open", "pages=${opened.media.size} title.length=${opened.title.orEmpty().length}")

        val pageUrl = checkNotNull(opened.media.first().url)
        val throughExtension = timed("image") { fetch(extensionClient, pageUrl) }
        report("image", "through extension: $throughExtension")
        report("image", "plain request: ${runCatching { fetch(OkHttpClient(), pageUrl) }.getOrElse { "failed: $it" }}")
        assertTrue("The page did not arrive as an image: $throughExtension", throughExtension.startsWith("image "))
    }

    /** Some series list no chapter in the source's language, so open listed series until one has pages. */
    private suspend fun firstReadable(adapter: MihonSourceAdapter, listed: List<Post>): Post =
        listed.take(MAX_SERIES_TRIED).firstNotNullOfOrNull { adapter.resolvePost(it.id) }
            ?: error("None of the first $MAX_SERIES_TRIED series had a readable chapter")

    private fun query(source: Source, sort: SortMode) = Query(
        mode = QueryMode.Source(MihonSourceAdapter.sourceKey(source)),
        includeTags = emptyList(),
        excludeTags = emptyList(),
        sort = sort,
        dateRange = null,
        minScore = null,
    )

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
