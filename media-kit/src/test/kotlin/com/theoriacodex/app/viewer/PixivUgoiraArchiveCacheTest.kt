package com.theoriacodex.app.viewer

import android.app.Application
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PixivUgoiraArchiveCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `clearing the disposable cache while an archive downloads keeps it and reports false (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val written = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = temporaryFolder.fixtureClient(directory, download = { _, _, file ->
            writeFrameZip(file)
            written.complete(Unit)
            release.await(5, TimeUnit.SECONDS)
            BinaryResponse(200)
        })
        val warming = async { client.warmArchive("busy") }
        written.await()

        assertFalse(client.clearDisposableCache())
        assertTrue(File(directory, "busy.zip").isFile)

        release.countDown()
        assertTrue(warming.await())
        assertTrue(client.clearDisposableCache())
        assertEquals(emptyList<String>(), directory.list().orEmpty().toList())
    }

    @Test
    fun `an archive that fails validation leaves neither the archive nor its timing (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val client = temporaryFolder.fixtureClient(directory, frames = """[{"file":"0001.png","delay":50},{"file":"0002.png","delay":50}]""")

        assertTrue(client.load("partial", UgoiraSizeBucket.CARD).isFailure)

        assertEquals(emptyList<String>(), directory.list().orEmpty().toList())

        val inflating = temporaryFolder.fixtureClient(directory, download = { _, _, file ->
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                zip.putNextEntry(ZipEntry("0001.png"))
                val block = ByteArray(64 * 1024)
                repeat((UGOIRA_MAX_FRAME_EXPANDED_BYTES / block.size).toInt() + 1) { zip.write(block) }
                zip.closeEntry()
            }
            BinaryResponse(200)
        })
        assertTrue(inflating.load("inflating", UgoiraSizeBucket.CARD).isFailure)
        assertEquals(emptyList<String>(), directory.list().orEmpty().toList())
    }

    @Test
    fun `a cached archive that fails to decode loses its timing, so the next load validates it again (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val broken = temporaryFolder.fixtureClient(directory, decode = { _, _, _, _ -> throw IOException("damaged frame") })

        assertTrue(broken.load("damaged", UgoiraSizeBucket.CARD).isFailure)
        assertFalse(File(directory, "damaged.zip.json").exists())

        val reopened = temporaryFolder.fixtureClient(directory, download = { _, _, _ -> error("a valid archive is reused") })
        assertTrue(reopened.load("damaged", UgoiraSizeBucket.CARD).isSuccess)
        assertTrue(File(directory, "damaged.zip.json").exists())
    }

    @Test
    fun `the client that failed to decode an archive validates it again before the next load (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val decodes = AtomicInteger()
        val client = temporaryFolder.fixtureClient(directory, decode = { file, frames, size, publish ->
            if (decodes.incrementAndGet() == 1) throw IOException("damaged frame")
            decodeUgoiraFrames(file, frames, size, publish)
        })

        assertTrue(client.load("damaged", UgoiraSizeBucket.CARD).isFailure)
        assertFalse(File(directory, "damaged.zip.json").exists())

        assertTrue(client.load("damaged", UgoiraSizeBucket.CARD).isSuccess)
        assertTrue(File(directory, "damaged.zip.json").exists())
    }

    @Test
    fun `a cached archive whose bytes changed under a matching sidecar is downloaded again (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val downloads = AtomicInteger()
        val download: (String, String, File) -> BinaryResponse = { _, _, file ->
            downloads.incrementAndGet()
            writeFrameZip(file)
            BinaryResponse(200)
        }
        assertTrue(temporaryFolder.fixtureClient(directory, download = download).warmArchive("swapped"))
        val archive = File(directory, "swapped.zip")
        val modified = archive.lastModified()
        archive.writeBytes(ByteArray(archive.length().toInt()))
        archive.setLastModified(modified)
        assertNotNull(readUgoiraFrameMetadata(archive))

        assertTrue(temporaryFolder.fixtureClient(directory, download = download).load("swapped", UgoiraSizeBucket.CARD).isSuccess)
        assertEquals(2, downloads.get())
    }

    @Test
    fun `an offline archive that fails validation is left untouched (ADR 0112)`() = runTest {
        val offline = File(temporaryFolder.newFolder("offline"), "animation.zip").apply { writeBytes(ByteArray(64) { 7 }) }
        writeUgoiraFrameMetadata(offline, listOf(UgoiraFrameSpec("0001.png", 50)))
        val client = PixivUgoiraClient(
            credentialsProvider = FixedCredentialsProvider,
            httpClient = UnusedHttpClient,
            archiveDirectory = temporaryFolder.newFolder("cache"),
            offlineArchiveLookup = { offline },
            metadataFetcher = { _, _ ->
                TextResponse(200, """{"ugoira_metadata":{"zip_urls":{"medium":"https://example.test/a.zip"},"frames":[{"file":"0001.png","delay":50}]}}""")
            },
            zipDownloader = { _, _, file ->
                writeFrameZip(file)
                BinaryResponse(200)
            },
        )

        assertTrue(client.load("pinned", UgoiraSizeBucket.CARD).isSuccess)

        assertEquals(ByteArray(64) { 7 }.toList(), offline.readBytes().toList())
    }

    @Test
    fun `pruning the archive store leaves an archive still being decoded (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        repeat(130) { index -> File(directory, "stale-$index.zip").apply { writeBytes(byteArrayOf(1)); setLastModified(2_000L + index) } }
        val decoding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = temporaryFolder.fixtureClient(
            directory,
            download = { _, _, file ->
                writeFrameZip(file)
                if (file.name == "playing.zip") file.setLastModified(1_000L)
                BinaryResponse(200)
            },
            decode = { file, frames, size, publish ->
                decoding.complete(Unit)
                release.await()
                decodeUgoiraFrames(file, frames, size, publish)
            },
        )
        val playing = async { client.load("playing", UgoiraSizeBucket.CARD) }
        decoding.await()

        assertTrue(client.warmArchive("fresh"))
        assertTrue(File(directory, "playing.zip").isFile)

        release.complete(Unit)
        assertTrue(playing.await().isSuccess)
    }

    @Test
    fun `a cached archive that no longer validates is downloaded again (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val downloads = AtomicInteger()
        val download: (String, String, File) -> BinaryResponse = { _, _, file ->
            downloads.incrementAndGet()
            writeFrameZip(file)
            BinaryResponse(200)
        }
        assertTrue(temporaryFolder.fixtureClient(directory, decode = { _, _, _, _ -> throw IOException("damaged frame") }, download = download)
            .load("damaged", UgoiraSizeBucket.CARD).isFailure)
        File(directory, "damaged.zip").writeBytes(ByteArray(64) { 7 })

        assertTrue(temporaryFolder.fixtureClient(directory, download = download).load("damaged", UgoiraSizeBucket.CARD).isSuccess)
        assertEquals(2, downloads.get())
    }

    @Test
    fun `an offline archive that fails to decode keeps its timing (ADR 0112)`() = runTest {
        val offline = File(temporaryFolder.newFolder("offline"), "animation.zip")
        temporaryFolder.fixtureClient().copyArchiveForOffline("pinned", offline)
        val reopened = PixivUgoiraClient(
            credentialsProvider = CancellingCredentialsProvider(CancellationException("Network was reached")),
            httpClient = UnusedHttpClient,
            archiveDirectory = temporaryFolder.newFolder("empty-cache"),
            offlineArchiveLookup = { offline },
            decode = { _, _, _, _ -> throw IOException("damaged frame") },
        )

        assertTrue(reopened.load("pinned", UgoiraSizeBucket.CARD).isFailure)

        assertTrue(offline.isFile)
        assertNotNull(readUgoiraFrameMetadata(offline))
    }

    @Test
    fun `an archive over the compressed limit is not downloaded again (ADR 0112)`() = runTest {
        val downloads = AtomicInteger()
        val client = temporaryFolder.fixtureClient(download = { _, _, _ ->
            downloads.incrementAndGet()
            throw UgoiraArchiveTooLarge()
        })

        assertTrue(client.load("huge", UgoiraSizeBucket.CARD).isFailure)
        assertEquals(1, downloads.get())
        assertEquals(FrameSample.Unreadable, client.sampleFrames("huge", count = 2, sizePx = 2, NetworkBudget(maxBytes = 1L shl 20)))
        assertEquals(2, downloads.get())
    }

    @Test
    fun `clearing the disposable cache while playback decodes keeps the archive and reports false (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val decoding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = temporaryFolder.fixtureClient(directory, decode = { file, frames, size, publish ->
            decoding.complete(Unit)
            release.await()
            decodeUgoiraFrames(file, frames, size, publish)
        })
        val playing = async { client.load("playing", UgoiraSizeBucket.CARD) }
        decoding.await()

        assertFalse(client.clearDisposableCache())
        assertTrue(File(directory, "playing.zip").isFile)

        release.complete(Unit)
        assertTrue(playing.await().isSuccess)
    }

    @Test
    fun `pruning the archive store leaves an archive still being fetched (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        repeat(130) { index -> File(directory, "stale-$index.zip").apply { writeBytes(byteArrayOf(1)); setLastModified(2_000L + index) } }
        val fetching = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = temporaryFolder.fixtureClient(directory, download = { _, _, file ->
            writeFrameZip(file)
            if (file.name == "busy.zip") {
                file.setLastModified(1_000L)
                fetching.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            BinaryResponse(200)
        })
        val busy = async { client.warmArchive("busy") }
        runCurrent()
        assertTrue(fetching.await(5, TimeUnit.SECONDS))

        assertTrue(client.warmArchive("fresh"))
        assertTrue(File(directory, "busy.zip").isFile)
        assertFalse(File(directory, "stale-0.zip").exists())

        release.countDown()
        assertTrue(busy.await())
    }
}
