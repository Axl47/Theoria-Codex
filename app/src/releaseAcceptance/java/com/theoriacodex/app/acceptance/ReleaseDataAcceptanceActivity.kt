package com.theoriacodex.app.acceptance

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.theoriacodex.app.codex.CodexShareFile
import com.theoriacodex.app.codex.CodexSharePost
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.update.FileBackedUpdateStateStore
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoVariant
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Installed only in the minified acceptance APK; write/read phases straddle a process restart. */
class ReleaseDataAcceptanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra("token").orEmpty()
        val phase = intent.getStringExtra("phase").orEmpty()
        require(token.matches(Regex("[a-f0-9]{24}")) && phase in setOf("write", "read"))
        val status = TextView(this).also { setContentView(it) }
        status.text = "Checking minified data contracts"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { verifyData(File(cacheDir, "release-contract-$token"), phase) }
                status.text = "Minified data contracts passed: $phase"
                Log.i(RESULT_TAG, "PASS:$token:$phase")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status.text = "Minified data contracts failed"
                Log.e(RESULT_TAG, "FAIL:$token:$phase:${failure.javaClass.simpleName}")
            }
        }
    }
}

private suspend fun verifyData(directory: File, phase: String) {
    val gson = GsonBuilder().serializeNulls().create()
    val updateFile = File(directory, "update.json")
    val shareFile = File(directory, "collection.json")
    val postFile = File(directory, "post.json")
    val legacyPostFile = File(directory, "legacy-post.json")
    val tagsFile = File(directory, "tags.json")
    if (phase == "write") {
        check(directory.mkdirs())
        FileBackedUpdateStateStore(updateFile).apply {
            setIgnoredRelease(RELEASE_ID)
            setRemindLater(RELEASE_ID, REMIND_UNTIL)
        }
        shareFile.writeText(gson.toJson(CodexShareFile(
            title = "Acceptance collection",
            posts = listOf(CodexSharePost(source = "PIXIV", sourcePostId = "42")),
        )))
        postFile.writeText(gson.toJson(PostStorageCodec.encode(videoPostFixture())))
        legacyPostFile.writeText(LEGACY_VIDEO_POST)
        tagsFile.writeText("""{"sources":{"GELBOORU":[{"text":"legacy","type":"trending","count":9}]}}""")
        val tags = FileBackedTagSuggestionStore(tagsFile, persistenceDebounceMs = 0L)
        try {
            tags.awaitLoaded()
            check(tags.get(SourceKey.GELBOORU, 10).any { it.text == "legacy" })
            tags.put(SourceKey.GELBOORU, listOf(TagSuggestion("fresh", "seen", 10)))
            tags.flush()
        } finally {
            tags.close()
        }
    } else {
        val snapshot = FileBackedUpdateStateStore(updateFile).snapshot()
        check(snapshot.ignoredReleaseId == RELEASE_ID)
        check(snapshot.remindLaterReleaseId == RELEASE_ID && snapshot.remindLaterUntilEpochMs == REMIND_UNTIL)
        val wire = JsonParser.parseString(shareFile.readText()).asJsonObject
        check(wire.keySet() == setOf("version", "title", "posts"))
        val share = gson.fromJson(shareFile.readText(), CodexShareFile::class.java)
        check(share.title == "Acceptance collection" && share.posts?.single()?.sourcePostId == "42")
        verifyVideoPost(postFile.readText())
        val legacyPost = requireNotNull(PostStorageCodec.decode(gson.fromJson(legacyPostFile.readText(), PostStorageRecord::class.java)))
        check(legacyPost.id == PostId(SourceKey.IWARA, "legacy-video"))
        check(legacyPost.preview.url == "https://example.test/preview.mp4" && legacyPost.preview.videoVariants.isEmpty())
        check(legacyPost.full?.url == "https://example.test/full.mp4" && legacyPost.full?.videoVariants == emptyList<VideoVariant>())
        check(legacyPost.media.single().url == "https://example.test/gallery.webm" && legacyPost.media.single().videoVariants.isEmpty())
        val tags = FileBackedTagSuggestionStore(tagsFile, persistenceDebounceMs = 0L)
        try {
            tags.awaitLoaded()
            check(tags.get(SourceKey.GELBOORU, 10).any { it.text == "fresh" })
        } finally {
            tags.close()
        }
        check(directory.deleteRecursively())
    }
}

private fun verifyVideoPost(json: String) {
    val gson = GsonBuilder().serializeNulls().create()
    val wire = JsonParser.parseString(json).asJsonObject
    val fullVariants = wire.getAsJsonArray("fullVideoVariants")
    check(fullVariants.size() == 2)
    fullVariants.forEach { check(it.asJsonObject.keySet() == setOf("url", "height", "original", "mime")) }
    val galleryMedia = wire.getAsJsonArray("media").single().asJsonObject
    check(galleryMedia.keySet() == setOf("url", "localPath", "mime", "progressiveUrls", "videoVariants", "isAnimated"))
    check(galleryMedia.getAsJsonArray("videoVariants").single().asJsonObject.keySet() == setOf("url", "height", "original", "mime"))
    val restored = requireNotNull(PostStorageCodec.decode(gson.fromJson(json, PostStorageRecord::class.java)))
    check(restored == videoPostFixture())
    check(restored.preview.videoVariants.isEmpty())
}

private fun videoPostFixture() = Post(
    id = PostId(SourceKey.IWARA, "video-contract"),
    preview = ImageRef("https://example.test/preview.mp4", null, "video/mp4", isAnimated = true),
    full = ImageRef("https://example.test/original.mp4", null, "video/mp4", videoVariants = listOf(
        VideoVariant("https://example.test/original.mp4", original = true),
        VideoVariant("https://example.test/480.mp4", height = 480, mime = "video/mp4"),
    )),
    media = listOf(ImageRef("https://example.test/gallery.webm", null, "video/webm", videoVariants = listOf(
        VideoVariant("https://example.test/gallery-720.webm", height = 720, mime = "video/webm"),
    ))),
    pageUrl = "https://example.test/video-contract",
    width = 1920,
    height = 1080,
    canonicalTags = emptyList(),
    rawTags = emptyList(),
    authorName = null,
    createdAtEpochMs = null,
)

private const val LEGACY_VIDEO_POST = """{
    "source":"IWARA","sourcePostId":"legacy-video",
    "previewUrl":"https://example.test/preview.mp4","previewMime":"video/mp4",
    "fullUrl":"https://example.test/full.mp4","fullMime":"video/mp4",
    "media":[{"url":"https://example.test/gallery.webm","mime":"video/webm"}]
}"""

private const val RESULT_TAG = "TheoriaReleaseData"
private const val RELEASE_ID = 42L
private const val REMIND_UNTIL = 123_456L
