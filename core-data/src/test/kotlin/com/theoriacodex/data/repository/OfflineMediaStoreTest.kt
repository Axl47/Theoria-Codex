package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.VideoVariant
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class OfflineMediaStoreTest : FileBackedRepositoryTestFixture() {
    @Test
    fun `100 and 1000 saves scan the root once and decode only changed records`() = runTest {
        for (size in listOf(100, 1_000)) {
            val scans = java.util.concurrent.atomic.AtomicInteger()
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val directory = object : File(tempDir("catalog-$size").path) {
                override fun listFiles(): Array<File>? { scans.incrementAndGet(); return super.listFiles() }
            }
            val manifests = object : KSerializer<OfflineManifest> by OfflineManifest.serializer() {
                override fun deserialize(decoder: Decoder): OfflineManifest {
                    reads.incrementAndGet()
                    return OfflineManifest.serializer().deserialize(decoder)
                }
            }
            val store = OfflineMediaStore(directory, Dispatchers.IO, manifests)
            val posts = (1..size).map { samplePost(it.toString(), null) }
            for (post in posts) {
                store.save(post, listOf(requireNotNull(post.full)), "all") { _, file -> file.writeText("media"); null }
                store.retain(post.id, "shared")
            }
            assertEquals(1, scans.get())
            assertEquals(size, reads.get())
            assertEquals(size, store.snapshot.value!!.postCount)
            val ids = posts.mapTo(HashSet(), Post::id)
            assertEquals(mapOf("all" to ids, "shared" to ids), store.snapshot.value!!.postsByOwner)
            store.load()
            assertEquals(1, scans.get())
            val beforeRefresh = store.snapshot.value
            store.refresh()
            assertEquals(beforeRefresh, store.snapshot.value)
            assertEquals(size * 2, reads.get())
            store.removeOwner("shared")
            assertEquals(mapOf("all" to ids), store.snapshot.value!!.postsByOwner)
        }
    }

    @Test
    fun `an unscanned store publishes no catalog until its scan finishes`() = runTest {
        val directory = tempDir("unscanned")
        val post = gallery()
        OfflineMediaStore(directory).save(post, post.media, "codex:a") { ref, file -> file.writeText(ref.url!!); null }

        val reopened = OfflineMediaStore(directory)
        assertNull(reopened.snapshot.value)
        val first = reopened.scannedSnapshots.first()

        assertEquals(mapOf("codex:a" to setOf(post.id)), first.postsByOwner)
        assertEquals(first, reopened.load())
    }

    @Test
    fun `complete galleries survive restart and share one copy across collection owners`() = runTest {
        val directory = tempDir("offline")
        val post = gallery()
        val store = OfflineMediaStore(directory)
        val completed = store.save(post, post.media, "codex:a") { ref, file -> file.writeText(ref.url!!); null }
        assertEquals(directory.walkTopDown().filter(File::isFile).sumOf(File::length), store.snapshot.value!!.bytes)
        assertEquals(2, completed.media.size)
        assertEquals(completed.media.first(), completed.full)
        assertTrue(completed.media.all { File(it.localPath!!).readText() == it.url })
        assertEquals(completed, store.retain(post.id, "codex:b"))

        val restored = OfflineMediaStore(directory)
        restored.refresh()
        assertEquals(completed, restored.find(post.id))
        assertEquals(mapOf("codex:a" to setOf(post.id), "codex:b" to setOf(post.id)), restored.snapshot.value!!.postsByOwner)
        assertEquals(2, restored.snapshot.value!!.mediaCount)
        assertEquals(directory.walkTopDown().filter(File::isFile).sumOf(File::length), restored.snapshot.value!!.bytes)

        restored.removeOwner("codex:a")
        assertNotNull(restored.find(post.id))
        restored.removeOwner("codex:b")
        assertNull(restored.find(post.id))
        assertEquals(0L, restored.snapshot.value!!.bytes)
    }

    @Test
    fun `removing one post from an owner keeps its other posts, another owner's copy and its admitted work`() = runTest {
        val store = OfflineMediaStore(tempDir("one-post"))
        val (a, b, c) = listOf("a", "b", "c").map { samplePost(it, null) }
        val admission = store.admit("series")
        for (post in listOf(a, b)) store.save(post, listOf(requireNotNull(post.full)), "series", admission) { _, file -> file.writeText("media"); null }
        store.retain(a.id, "codex:x")

        store.removeOwner("series", a.id)
        store.removeOwner("series", b.id)
        assertEquals(mapOf("codex:x" to setOf(a.id)), store.snapshot.value!!.postsByOwner)
        assertNotNull(store.find(a.id))
        assertNull(store.find(b.id))
        store.save(c, listOf(requireNotNull(c.full)), "series", admission) { _, file -> file.writeText("media"); null }
        assertEquals(setOf(c.id), store.snapshot.value!!.postsByOwner["series"])
    }

    @Test
    fun `a save acquires up to its parallel media at once and keeps gallery order`() = runTest {
        val store = OfflineMediaStore(tempDir("parallel"))
        val post = gallery()
        val secondStarted = CompletableDeferred<Unit>()
        val saved = store.save(post, post.media, "series", parallel = 2) { ref, file ->
            if (ref == post.media.first()) secondStarted.await() else secondStarted.complete(Unit)
            file.writeText(ref.url!!)
            null
        }
        assertEquals(post.media.map { it.url }, saved.media.map { File(it.localPath!!).readText() })
    }

    @Test
    fun `a delete stopped partway leaves no copy that opens after a restart`() = runTest {
        val directory = tempDir("stopped-delete")
        val post = gallery()
        OfflineMediaStore(directory).save(post, post.media, "codex:a") { _, file -> file.writeText("bytes"); null }
        val folder = directory.listFiles().orEmpty().single()
        val generation = folder.listFiles().orEmpty().single(File::isDirectory)
        generation.setWritable(false)
        try {
            OfflineMediaStore(directory).apply { load() }.removeOwner("codex:a")
            assertTrue(generation.listFiles().orEmpty().isNotEmpty())
            assertNull(OfflineMediaStore(directory).find(post.id))
        } finally {
            generation.setWritable(true)
        }
        val reopened = OfflineMediaStore(directory).apply { load() }
        assertFalse(folder.exists())
        assertEquals(0L, reopened.snapshot.value!!.bytes)
    }

    @Test
    fun `a folder without a manifest is removed on load, and one with an unreadable manifest is kept`() = runTest {
        val directory = tempDir("no-manifest")
        val (kept, stopped) = listOf("kept", "stopped").map { samplePost(it, null) }
        for (post in listOf(kept, stopped)) {
            OfflineMediaStore(directory).save(post, listOf(requireNotNull(post.full)), "codex:a") { _, file -> file.writeText("bytes"); null }
        }
        val folders = directory.listFiles().orEmpty().toList()
        val (unreadable, manifestless) = folders.partition { "kept" in it.resolve("manifest.json").readText() }
        unreadable.single().resolve("manifest.json").writeText("{not json")
        manifestless.single().resolve("manifest.json").delete()

        val store = OfflineMediaStore(directory).apply { load() }
        assertFalse(manifestless.single().exists())
        assertTrue(unreadable.single().exists())
        assertEquals(unreadable.single().walkTopDown().filter(File::isFile).sumOf(File::length), store.snapshot.value!!.bytes)
    }

    @Test
    fun `failed replacement and cancelled gallery leave completed bytes intact`() = runTest {
        val directory = tempDir("failed")
        val post = gallery()
        val store = OfflineMediaStore(directory)
        val completed = store.save(post, post.media, "saved-posts") { _, file -> file.writeText("original"); null }
        var failed = false
        try {
            store.save(post, post.media, "codex:b") { _, file ->
                file.writeText("partial")
                throw IOException("disconnected")
            }
        } catch (_: IOException) { failed = true }
        assertTrue(failed)
        assertEquals(completed, store.find(post.id))
        val started = CompletableDeferred<Unit>()
        val cancelled = async {
            store.save(post, post.media, "codex:b") { _, file ->
                file.writeText("partial")
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        cancelled.cancelAndJoin()
        assertEquals(completed, store.find(post.id))
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(".pending-") })
    }

    @Test
    fun `clear or owner removal invalidates an already running acquisition`() = runTest {
        for (clearAll in listOf(false, true)) {
            val store = OfflineMediaStore(tempDir("clear-$clearAll"))
            val post = gallery()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val saving = async {
                runCatching {
                    store.save(post, post.media, "codex:a") { _, file ->
                        started.complete(Unit)
                        finish.await()
                        file.writeText("complete")
                        null
                    }
                }
            }
            started.await()
            if (clearAll) store.clear() else store.removeOwner("codex:a")
            finish.complete(Unit)
            assertTrue(saving.await().isFailure)
            assertNull(store.find(post.id))
        }
    }

    @Test
    fun `missing truncated and incomplete media never appears available`() = runTest {
        val directory = tempDir("integrity")
        val store = OfflineMediaStore(directory)
        val post = gallery()
        assertNull(store.retain(post.id, "missing"))
        try {
            store.save(post, post.media.take(1), "codex:a") { _, file -> file.writeText("bytes"); null }
            error("Incomplete gallery was accepted")
        } catch (_: IllegalArgumentException) { }
        val saved = store.save(post, post.media, "codex:a") { _, file -> file.writeText("complete"); null }
        File(saved.media.last().localPath!!).writeText("short")
        assertNull(store.find(post.id))
        store.refresh()
        assertEquals(0, store.snapshot.value!!.postCount)
        assertTrue(store.snapshot.value!!.bytes > 0)
        store.clear()
        assertEquals(0L, store.snapshot.value!!.bytes)
    }

    @Test
    fun `a later owner retains the completed generation and all previously returned paths`() = runTest {
        val directory = tempDir("replace")
        val store = OfflineMediaStore(directory)
        val post = gallery()
        val first = store.save(post, post.media, "codex:a") { _, file -> file.writeText("first"); null }
        store.save(post, post.media, "codex:b") { _, file -> file.writeText("second"); null }
        assertTrue(first.media.all { File(it.localPath!!).readText() == "first" })
        assertEquals(mapOf("codex:a" to setOf(post.id), "codex:b" to setOf(post.id)), store.snapshot.value!!.postsByOwner)
        assertEquals(1, directory.listFiles().orEmpty().single().listFiles().orEmpty().count(File::isDirectory))
    }

    @Test
    fun `offline overlays strip only owned paths even after media was removed`() = runTest {
        val directory = tempDir("strip")
        val store = OfflineMediaStore(directory)
        val post = gallery()
        val saved = store.save(post, post.media, "codex:a") { _, file -> file.writeText("bytes"); null }
        store.clear()
        val unrelated = post.media.last().copy(localPath = File(directory.parentFile, "fixture.png").absolutePath)
        val overlaid = saved.copy(media = saved.media + unrelated)
        val clean = store.withoutOfflineLocations(overlaid)
        assertNull(clean.preview.localPath)
        assertNull(clean.full?.localPath)
        assertTrue(clean.media.take(2).all { it.localPath == null })
        assertEquals(unrelated, clean.media.last())
        assertEquals(post.full?.url, clean.full?.url)
        assertEquals(clean, store.withoutOfflineLocations(clean))
    }

    @Test
    fun `selected offline rendition keeps source media through restart ownership changes and removal`() = runTest {
        val directory = tempDir("video-rendition")
        val original = ImageRef(
            url = "https://example.test/original.mp4", localPath = null, mime = "video/mp4",
            videoVariants = listOf(VideoVariant("https://example.test/small.webm", 480, mime = "video/webm")),
        )
        val source = samplePost("video", null).copy(full = original, media = emptyList(), mediaCount = null)
        val selected = original.copy(url = original.videoVariants.single().url, mime = "video/webm")
        val firstStore = OfflineMediaStore(directory)
        val first = firstStore.save(source, listOf(selected), "codex:a") { _, file -> file.writeText("webm bytes"); null }
        assertEquals(selected.url, first.full?.url)
        assertEquals("video/webm", first.full?.mime)
        assertTrue(File(requireNotNull(first.full?.localPath)).isFile)
        assertEquals(source, firstStore.withoutOfflineLocations(first))
        assertEquals(source.copy(title = "Viewer update"), firstStore.withoutOfflineLocations(first.copy(title = "Viewer update")))
        firstStore.retain(source.id, "codex:b")

        val reopened = OfflineMediaStore(directory)
        val retained = requireNotNull(reopened.find(source.id))
        assertEquals(source, reopened.withoutOfflineLocations(retained))
        reopened.removeOwner("codex:a")
        val remainingStore = OfflineMediaStore(directory)
        val afterOwnerRemoval = requireNotNull(remainingStore.find(source.id))
        assertEquals("video/webm", afterOwnerRemoval.full?.mime)
        assertEquals(source, remainingStore.withoutOfflineLocations(afterOwnerRemoval))
        reopened.clear()
        assertEquals(source, reopened.withoutOfflineLocations(retained.copy()))
        assertEquals(source, firstStore.withoutOfflineLocations(first))
        assertNull(reopened.find(source.id))
    }

    @Test
    fun `gallery cleanup restores per-page source refs and leaves unrelated local media intact`() = runTest {
        val directory = tempDir("gallery-rendition")
        val source = gallery().copy(mediaCount = 2)
        val selected = source.media.map { it.copy(url = it.url + "?rendition=small", mime = "image/webp") }
        val store = OfflineMediaStore(directory)
        val offline = store.save(source, selected, "codex:a") { _, file -> file.writeText("selected"); null }
        val secondRead = requireNotNull(store.find(source.id))
        store.clear()
        assertEquals(source, store.withoutOfflineLocations(offline))
        assertEquals(source, store.withoutOfflineLocations(secondRead))
        val unrelated = selected.last().copy(localPath = File(directory.parentFile, "fixture.webp").path)
        val mixed = offline.copy(media = offline.media + unrelated)
        val cleaned = store.withoutOfflineLocations(mixed)
        assertEquals(source.media + unrelated, cleaned.media)
        assertEquals(source.preview, cleaned.preview)
        assertEquals(source.full, cleaned.full)
    }

    @Test
    fun `legacy offline manifests without canonical provenance never claim availability`() = runTest {
        val directory = tempDir("legacy-manifest")
        val store = OfflineMediaStore(directory)
        val source = gallery()
        val offline = store.save(source, source.media, "codex:a") { _, file -> file.writeText("bytes"); null }
        editManifest(directory) { root ->
            root["version"] = JsonPrimitive(1)
            root.remove("sourcePost")
        }
        val reopened = OfflineMediaStore(directory)
        reopened.refresh()
        assertNull(reopened.find(source.id))
        assertEquals(0, reopened.snapshot.value!!.postCount)
        assertTrue(offline.media.all { File(it.localPath!!).isFile })
    }

    @Test
    fun `new manifests are version 3 with stable keys and round-trip the full post`() = runTest {
        val directory = tempDir("v3")
        val base = gallery()
        val source = base.copy(
            full = base.full?.copy(videoVariants = listOf(VideoVariant("https://example.test/480.mp4", 480, mime = "video/mp4"))),
            taxonomy = listOf(PostTaxonomyTerm("cat", SearchFacet.TAG, "ns", 3, translation = "neko")),
            creatorProfiles = listOf(CreatorProfile(SourceKey.PIXIV, "artist", "1", "https://example.test/u/1", "user:1")),
        )
        val saved = OfflineMediaStore(directory).save(source, source.media, "codex:a") { _, file -> file.writeText("bytes"); null }
        val manifest = manifestOf(directory)
        assertEquals(3, manifest.getValue("version").jsonPrimitive.int)
        val wire = manifest.getValue("post").jsonObject
        assertTrue("sourcePostId" in wire && "previewUrl" in wire)
        assertTrue("fullVideoVariants" in manifest.getValue("sourcePost").jsonObject)
        assertTrue(saved.media.all { it.localPath != null })
        val reopened = OfflineMediaStore(directory)
        val restored = requireNotNull(reopened.find(source.id))
        assertEquals(saved, restored)
        assertEquals("neko", restored.taxonomy.single().translation)
        assertEquals(source, reopened.withoutOfflineLocations(restored))
    }

    @Test
    fun `a version 2 manifest is not a usable copy and a later save keeps its owners`() = runTest {
        val directory = tempDir("v2")
        val post = gallery()
        OfflineMediaStore(directory).save(post, post.media, "codex:a") { _, file -> file.writeText("bytes"); null }
        val folder = directory.listFiles().orEmpty().single()
        val oldGeneration = folder.listFiles().orEmpty().single(File::isDirectory)
        editManifest(directory) { root ->
            root["version"] = JsonPrimitive(2)
            root["owners"] = JsonArray(listOf(JsonPrimitive("codex:a"), JsonPrimitive("codex:z")))
        }

        val store = OfflineMediaStore(directory)
        assertNull(store.find(post.id))
        assertEquals(0, store.snapshot.value!!.postCount)
        store.save(post, post.media, "codex:b") { _, media -> media.writeText("again"); null }
        val manifest = manifestOf(directory)
        assertEquals(3, manifest.getValue("version").jsonPrimitive.int)
        assertEquals(listOf("codex:a", "codex:b", "codex:z"), ownersOf(manifest))
        assertFalse(oldGeneration.exists())
        assertNotNull(OfflineMediaStore(directory).find(post.id))
    }

    @Test
    fun `a version 3 manifest whose post cannot be decoded keeps its valid owners`() = runTest {
        val directory = tempDir("v3-undecodable")
        val post = gallery()
        OfflineMediaStore(directory).save(post, post.media, "codex:a") { _, file -> file.writeText("bytes"); null }
        editManifest(directory) { root ->
            root["post"] = JsonPrimitive("not a post")
            root["owners"] = JsonArray(listOf(JsonPrimitive("codex:a"), JsonObject(emptyMap()), JsonPrimitive("codex:z")))
        }

        val store = OfflineMediaStore(directory)
        assertNull(store.find(post.id))
        store.save(post, post.media, "codex:b") { _, media -> media.writeText("again"); null }
        assertEquals(listOf("codex:a", "codex:b", "codex:z"), ownersOf(manifestOf(directory)))
    }

    @Test
    fun `a manifest without readable owners is replaced without crashing`() = runTest {
        val directory = tempDir("no-owners")
        val post = gallery()
        OfflineMediaStore(directory).save(post, post.media, "codex:a") { _, file -> file.writeText("bytes"); null }
        editManifest(directory) { root ->
            root["version"] = JsonPrimitive(2)
            root["owners"] = JsonPrimitive("codex:a")
        }

        OfflineMediaStore(directory).save(post, post.media, "codex:b") { _, media -> media.writeText("again"); null }
        assertEquals(listOf("codex:b"), ownersOf(manifestOf(directory)))
    }

    @Test
    fun `removing the last owner of an undecodable copy deletes it, even after a restart`() = runTest {
        val directory = tempDir("undecodable-removal")
        val post = gallery()
        OfflineMediaStore(directory).save(post, post.media, "codex:a") { _, file -> file.writeText("bytes"); null }
        val folder = directory.listFiles().orEmpty().single()
        editManifest(directory) { root ->
            root["version"] = JsonPrimitive(2)
            root["owners"] = JsonArray(listOf(JsonPrimitive("codex:a"), JsonPrimitive("codex:z")))
        }

        val store = OfflineMediaStore(directory).apply { load() }
        assertEquals(mapOf("codex:a" to emptySet<PostId>(), "codex:z" to emptySet()), store.snapshot.value!!.postsByOwner)
        store.removeOwner("codex:a")
        assertEquals(listOf("codex:z"), ownersOf(manifestOf(directory)))
        assertEquals(2, manifestOf(directory).getValue("version").jsonPrimitive.int)

        OfflineMediaStore(directory).apply { load() }.removeOwner("codex:z")
        assertFalse(folder.exists())
    }

    @Test
    fun `a numeric owner never keeps a folder alive (ADR 0022)`() = runTest {
        val directory = tempDir("numeric-owner")
        val post = gallery()
        OfflineMediaStore(directory).save(post, post.media, "codex:a") { _, file -> file.writeText("bytes"); null }
        val folder = directory.listFiles().orEmpty().single()
        editManifest(directory) { root ->
            root["version"] = JsonPrimitive(2)
            root["owners"] = JsonArray(listOf(JsonPrimitive("codex:a"), JsonPrimitive(7)))
        }

        OfflineMediaStore(directory).apply { load() }.removeOwner("codex:a")

        assertFalse(folder.exists())
    }

    @Test
    fun `a copy with no recoverable owner stays when collections are removed`() = runTest {
        val directory = tempDir("ownerless")
        val post = gallery()
        OfflineMediaStore(directory).save(post, post.media, "codex:a") { _, file -> file.writeText("bytes"); null }
        val folder = directory.listFiles().orEmpty().single()
        val indexed = OfflineMediaStore(directory).apply { load() }
        manifestFile(directory).writeText("{not json")

        indexed.removeOwner("codex:a")
        OfflineMediaStore(directory).apply { load() }.removeOwner("codex:a")

        assertTrue(folder.resolve("manifest.json").isFile)
    }

    @Test
    fun `a manifest whose id does not hash to its folder is rejected`() = runTest {
        val directory = tempDir("misplaced")
        val post = gallery()
        OfflineMediaStore(directory).save(post, post.media, "codex:a") { _, file -> file.writeText("bytes"); null }
        editManifest(directory) { root ->
            for (name in listOf("post", "sourcePost")) {
                root[name] = JsonObject(root.getValue(name).jsonObject + ("sourcePostId" to JsonPrimitive("other")))
            }
        }
        val store = OfflineMediaStore(directory)
        store.refresh()
        assertEquals(0, store.snapshot.value!!.postCount)
    }

    @Test
    fun `a page outside its copy's folder is not a usable copy`() = runTest {
        val outside = tempDir("outside").resolve("page.png").apply { writeText("bytes") }
        for ((name, escape) in listOf<Pair<String, (File) -> String>>(
            "dot-dot" to { page -> "${page.parent}/../../../escaped.png" },
            "absolute" to { _ -> outside.absolutePath },
        )) {
            val directory = tempDir(name)
            val post = gallery()
            val saved = OfflineMediaStore(directory).save(post, post.media, "codex:a") { _, file -> file.writeText("bytes"); null }
            val page = File(saved.media.first().localPath!!)
            val target = escape(page)
            File(target).writeText("bytes")
            editManifest(directory) { root ->
                val media = root.getValue("post").jsonObject.getValue("media").jsonArray
                val moved = JsonObject(media.first().jsonObject + ("localPath" to JsonPrimitive(target)))
                root["post"] = JsonObject(root.getValue("post").jsonObject + ("media" to JsonArray(listOf(moved) + media.drop(1))))
            }

            val store = OfflineMediaStore(directory).apply { load() }
            assertNull(name, store.find(post.id))
            assertEquals(name, 0, store.snapshot.value!!.postCount)
        }
    }

    private fun manifestFile(directory: File) = directory.listFiles().orEmpty().single().resolve("manifest.json")

    private fun manifestOf(directory: File): JsonObject = Json.parseToJsonElement(manifestFile(directory).readText()).jsonObject

    private fun ownersOf(manifest: JsonObject) = manifest.getValue("owners").jsonArray.map { it.jsonPrimitive.content }

    private fun editManifest(directory: File, edit: (MutableMap<String, JsonElement>) -> Unit) {
        val file = manifestFile(directory)
        file.writeText(JsonObject(manifestOf(directory).toMutableMap().also(edit)).toString())
    }

    private fun gallery(): Post {
        val post = samplePost("42", null)
        val first = requireNotNull(post.full)
        return post.copy(media = listOf(first, first.copy(url = "https://example.test/page2.png")), mediaCount = 2)
    }
}
