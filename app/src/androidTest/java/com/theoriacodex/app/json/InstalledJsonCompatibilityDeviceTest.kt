package com.theoriacodex.app.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.theoriacodex.app.codex.CodexShareFile
import com.theoriacodex.app.codex.CodexSharePost
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.update.ChangelogSection
import com.theoriacodex.app.update.PendingPostInstallChangelog
import com.theoriacodex.app.update.UpdateStateSnapshot
import com.theoriacodex.data.storage.ImageRefStorageRecord
import com.theoriacodex.data.storage.LegacyImportProof
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.data.storage.VideoVariantRecord
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoVariant
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstalledJsonCompatibilityDeviceTest {
    private val gson = GsonBuilder().serializeNulls().create()

    @Test
    fun stableWireKeysSurviveTheInstalledTargetApk() {
        assertJsonKeys(
            LegacyImportProof(),
            "sourceFileName",
            "sourceSchemaVersion",
            "destinationSchemaVersion",
            "sourceSha256",
            "sourceByteCount",
            "importedCounts",
        )
        assertJsonKeys(
            PostStorageRecord(),
            "source",
            "sourcePostId",
            "previewUrl",
            "previewLocalPath",
            "previewMime",
            "previewProgressiveUrls",
            "previewIsAnimated",
            "fullUrl",
            "fullLocalPath",
            "fullMime",
            "fullProgressiveUrls",
            "fullVideoVariants",
            "fullIsAnimated",
            "pageUrl",
            "width",
            "height",
            "canonicalTags",
            "rawTags",
            "authorName",
            "createdAtEpochMs",
            "media",
            "title",
            "creatorProfile",
            "durationMs",
            "mediaCount",
            "taxonomy",
            "creatorProfiles",
            "schemaVersion",
        )
        assertJsonKeys(
            ImageRefStorageRecord(),
            "url", "localPath", "mime", "progressiveUrls", "videoVariants", "isAnimated",
        )
        assertJsonKeys(VideoVariantRecord(), "url", "height", "original", "mime")
        assertJsonKeys(
            UpdateStateSnapshot(
                pendingPostInstallChangelog = PendingPostInstallChangelog(
                    releaseId = 7L,
                    versionCode = 8,
                    tagName = "v0.0.8",
                    commitShaShort = "abcdef0",
                    changelogSections = listOf(ChangelogSection("Fixes", listOf("Stable JSON"))),
                ),
            ),
            "lastSeenReleaseId",
            "pendingInstallReleaseId",
            "pendingInstallVersionCode",
            "ignoredReleaseId",
            "remindLaterReleaseId",
            "remindLaterUntilEpochMs",
            "pendingPostInstallChangelog",
            "lastInstalledChangelog",
        )
        assertJsonKeys(
            CodexShareFile(posts = listOf(CodexSharePost(source = "PIXIV", sourcePostId = "42"))),
            "version",
            "title",
            "posts",
        )
    }

    @Test
    fun fullAndGalleryVideoVariantsRoundTripWithoutChangingThePreview() {
        val fullVariants = listOf(
            VideoVariant("https://example.test/original.mp4", original = true),
            VideoVariant("https://example.test/480.mp4", height = 480, mime = "video/mp4"),
        )
        val galleryVariants = listOf(
            VideoVariant("https://example.test/gallery-720.webm", height = 720, mime = "video/webm"),
        )
        val post = Post(
            id = PostId(SourceKey.IWARA, "video-contract"),
            preview = ImageRef("https://example.test/preview.mp4", null, "video/mp4", isAnimated = true),
            full = ImageRef("https://example.test/original.mp4", null, "video/mp4", videoVariants = fullVariants),
            media = listOf(ImageRef("https://example.test/gallery.webm", null, "video/webm", videoVariants = galleryVariants)),
            pageUrl = "https://example.test/video-contract",
            width = 1920,
            height = 1080,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )

        val encoded = gson.toJson(PostStorageCodec.encode(post))
        val wire = JSONObject(encoded)
        assertVariantWire(wire.getJSONArray("fullVideoVariants").getJSONObject(0), fullVariants[0])
        assertVariantWire(wire.getJSONArray("fullVideoVariants").getJSONObject(1), fullVariants[1])
        assertVariantWire(wire.getJSONArray("media").getJSONObject(0).getJSONArray("videoVariants").getJSONObject(0), galleryVariants[0])
        assertEquals(post.preview.url, wire.getString("previewUrl"))
        val restored = requireNotNull(PostStorageCodec.decode(gson.fromJson(encoded, PostStorageRecord::class.java)))
        assertEquals(post, restored)
        assertEquals(emptyList<VideoVariant>(), restored.preview.videoVariants)
        assertEquals(fullVariants, restored.full?.videoVariants)
        assertEquals(galleryVariants, restored.media.single().videoVariants)
    }

    @Test
    fun legacyVideoPostsWithoutVariantFieldsStillDecode() {
        val legacy = """{
            "source":"IWARA","sourcePostId":"legacy-video",
            "previewUrl":"https://example.test/preview.mp4","previewMime":"video/mp4",
            "fullUrl":"https://example.test/full.mp4","fullMime":"video/mp4",
            "media":[{"url":"https://example.test/gallery.webm","mime":"video/webm"}]
        }""".trimIndent()
        val restored = requireNotNull(PostStorageCodec.decode(gson.fromJson(legacy, PostStorageRecord::class.java)))
        assertEquals("https://example.test/preview.mp4", restored.preview.url)
        assertEquals("https://example.test/full.mp4", restored.full?.url)
        assertEquals("https://example.test/gallery.webm", restored.media.single().url)
        assertEquals(emptyList<VideoVariant>(), restored.preview.videoVariants)
        assertEquals(emptyList<VideoVariant>(), restored.full?.videoVariants)
        assertEquals(emptyList<VideoVariant>(), restored.media.single().videoVariants)
    }

    @Test
    fun tagCacheFixtureDecodesAndRewritesWithStableKeys() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "release-json-test/tag_suggestions.json")
        file.parentFile?.mkdirs()
        file.writeText(
            """{"sources":{"GELBOORU":[{"text":"legacy","type":"trending","count":9}]}}""",
        )
        val store = FileBackedTagSuggestionStore(storeFile = file, persistenceDebounceMs = 0L)
        try {
            store.awaitLoaded()
            assertEquals("legacy", store.get(SourceKey.GELBOORU, 1).single().text)
            store.put(SourceKey.GELBOORU, listOf(TagSuggestion("fresh", "seen", 10)))
            store.flush()
            val root = JSONObject(file.readText())
            val entries = root.getJSONObject("sources").getJSONArray("GELBOORU")
            val first = entries.getJSONObject(0)
            assertEquals(
                setOf("text", "facet", "type", "count", "origins"),
                first.keySetCompat(),
            )
            val records = (0 until entries.length()).map(entries::getJSONObject)
            val fresh = records.single { it.getString("text") == "fresh" }
            assertEquals("SEEN", fresh.getJSONArray("origins").getString(0))
        } finally {
            store.close()
            file.parentFile?.deleteRecursively()
        }
    }

    private fun assertJsonKeys(value: Any, vararg keys: String) {
        assertEquals(keys.toSet(), JSONObject(gson.toJson(value)).keySetCompat())
    }

    private fun assertVariantWire(wire: JSONObject, expected: VideoVariant) {
        assertEquals(setOf("url", "height", "original", "mime"), wire.keySetCompat())
        assertEquals(expected.url, wire.getString("url"))
        assertEquals(expected.height, if (wire.isNull("height")) null else wire.getInt("height"))
        assertEquals(expected.original, wire.getBoolean("original"))
        assertEquals(expected.mime, if (wire.isNull("mime")) null else wire.getString("mime"))
    }
}

private fun JSONObject.keySetCompat(): Set<String> = buildSet {
    val iterator = keys()
    while (iterator.hasNext()) add(iterator.next())
}
