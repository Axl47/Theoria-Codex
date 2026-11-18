package com.theoriacodex.app.acceptance

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theoriacodex.app.codex.codexSharePostSnapshot
import com.theoriacodex.app.codex.encodeCodexShareFile
import com.theoriacodex.app.codex.parseCodexShareFile
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.update.ChangelogSection
import com.theoriacodex.app.update.FileBackedUpdateStateStore
import com.theoriacodex.app.update.PendingPostInstallChangelog
import com.theoriacodex.app.update.UpdateStateSnapshot
import com.theoriacodex.data.repository.FileBackedQueryRepository
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoVariant
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    val legacyUpdateFile = File(directory, "update-legacy.json")
    val shareFile = File(directory, "collection.json")
    val postFile = File(directory, "post.json")
    val legacyPostFile = File(directory, "legacy-post.json")
    val tagsFile = File(directory, "tags.json")
    val dataStores = File(directory, "datastore")
    val shippedDataStores = File(directory, "datastore-shipped")
    if (phase == "write") {
        check(directory.mkdirs())
        FileBackedUpdateStateStore(updateFile).update { updateState }
        legacyUpdateFile.writeText(LEGACY_UPDATE_STATE)
        FileBackedQueryRepository(directory).upsertAppliedQuery(QUERY_MODE, acceptanceQuery)
        shareFile.writeText(encodeCodexShareFile("Acceptance & <collection>", listOf(videoPostFixture())))
        postFile.writeText(gson.toJson(PostStorageCodec.encode(videoPostFixture())))
        legacyPostFile.writeText(LEGACY_VIDEO_POST)
        writeLegacyOfflineManifest(File(directory, "offline"))
        writeDataStores(dataStores, shippedDataStores)
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
        verifyUpdateState(updateFile, legacyUpdateFile)
        verifyQueryStore(directory)
        verifyCodexShare(shareFile.readText())
        verifyVideoPost(postFile.readText())
        val legacyPost = requireNotNull(PostStorageCodec.decode(gson.fromJson(legacyPostFile.readText(), PostStorageRecord::class.java)))
        check(legacyPost.id == PostId(SourceKey.IWARA, "legacy-video"))
        check(legacyPost.preview.url == "https://example.test/preview.mp4" && legacyPost.preview.videoVariants.isEmpty())
        check(legacyPost.full?.url == "https://example.test/full.mp4" && legacyPost.full?.videoVariants == emptyList<VideoVariant>())
        check(legacyPost.media.single().url == "https://example.test/gallery.webm" && legacyPost.media.single().videoVariants.isEmpty())
        verifyLegacyOfflineManifest(File(directory, "offline"))
        verifyDataStores(dataStores, shippedDataStores)
        val tags = FileBackedTagSuggestionStore(tagsFile, persistenceDebounceMs = 0L)
        try {
            tags.awaitLoaded()
            check(tags.get(SourceKey.GELBOORU, 10).any { it.text == "fresh" })
            check(JsonParser.parseString(tagsFile.readText()).asJsonObject.getAsJsonObject("sources").has("GELBOORU"))
        } finally {
            tags.close()
        }
        check(directory.deleteRecursively())
    }
}

/** kotlinx serializers survive R8 with readable keys, and a file the Gson store wrote still loads. */
private suspend fun verifyUpdateState(updateFile: File, legacyUpdateFile: File) {
    check(FileBackedUpdateStateStore(updateFile).snapshot() == updateState)
    val wire = JsonParser.parseString(updateFile.readText()).asJsonObject
    check(wire.keySet() == UPDATE_STATE_KEYS)
    val changelog = wire.getAsJsonObject("pendingPostInstallChangelog")
    check(changelog.keySet() == CHANGELOG_KEYS)
    check(changelog.getAsJsonArray("changelogSections").single().asJsonObject.keySet() == setOf("title", "bullets"))
    val legacy = FileBackedUpdateStateStore(legacyUpdateFile).snapshot()
    check(legacy.ignoredReleaseId == RELEASE_ID && legacy.remindLaterUntilEpochMs == REMIND_UNTIL)
    check(legacy.pendingPostInstallChangelog?.releaseName == "Tom & Jerry's <release>")
}

private suspend fun verifyQueryStore(directory: File) {
    check(FileBackedQueryRepository(directory).observeAppliedQuery(QUERY_MODE).first() == acceptanceQuery)
    val wire = JsonParser.parseString(File(directory, "query_store.json").readText()).asJsonObject
    val record = wire.getAsJsonObject("queries").getAsJsonObject(QUERY_MODE)
    check(record.keySet() == QUERY_KEYS)
    check(record.getAsJsonArray("includeTerms").first().asJsonObject.keySet() == setOf("value", "facet", "sourceNamespace"))
}

private fun verifyCodexShare(json: String) {
    val wire = JsonParser.parseString(json).asJsonObject
    check(wire.keySet() == setOf("version", "title", "posts"))
    check(wire.getAsJsonArray("posts").single().asJsonObject.keySet() == setOf("source", "sourcePostId", "snapshot"))
    val imported = requireNotNull(parseCodexShareFile(json))
    check(imported.title == "Acceptance & <collection>")
    check(codexSharePostSnapshot(imported.posts.orEmpty().single())?.id == videoPostFixture().id)
}

private val updateState = PendingPostInstallChangelog(
    releaseId = RELEASE_ID,
    fromVersionCode = 1,
    versionCode = 2,
    tagName = "v0.0.2",
    commitShaShort = "abc1234",
    releaseName = "Acceptance & <release>",
    changelogMarkdown = "- fixed",
    changelogSections = listOf(ChangelogSection("Fixed", listOf("one"))),
).let { changelog ->
    UpdateStateSnapshot(
        lastSeenReleaseId = RELEASE_ID,
        pendingInstallReleaseId = RELEASE_ID,
        pendingInstallVersionCode = 2,
        ignoredReleaseId = RELEASE_ID,
        remindLaterReleaseId = RELEASE_ID,
        remindLaterUntilEpochMs = REMIND_UNTIL,
        pendingPostInstallChangelog = changelog,
        lastInstalledChangelog = changelog,
    )
}

private val acceptanceQuery = Query(
    mode = QueryMode.Source(SourceKey.PIXIV),
    includeTerms = listOf(SearchTerm("a & b", SearchFacet.ARTIST, "artist")),
    excludeTerms = listOf(SearchTerm("c")),
    sort = SortMode.NEWEST,
    dateRange = DateRange(1L, 2L),
    minScore = 3,
)

private val UPDATE_STATE_KEYS = setOf(
    "lastSeenReleaseId", "pendingInstallReleaseId", "pendingInstallVersionCode", "ignoredReleaseId",
    "remindLaterReleaseId", "remindLaterUntilEpochMs", "pendingPostInstallChangelog", "lastInstalledChangelog",
)

private val CHANGELOG_KEYS = setOf(
    "releaseId", "fromVersionCode", "versionCode", "tagName", "commitShaShort", "releaseName", "changelogMarkdown",
    "changelogSections",
)

private val QUERY_KEYS = setOf(
    "schemaVersion", "modeType", "modeSource", "includeTags", "excludeTags", "sort", "dateFromEpochMs", "dateToEpochMs",
    "minScore", "includeTerms", "excludeTerms", "includeTermGroups",
)

private fun verifyVideoPost(json: String) {
    val gson = GsonBuilder().serializeNulls().create()
    val wire = JsonParser.parseString(json).asJsonObject
    val fullVariants = wire.getAsJsonArray("fullVideoVariants")
    check(fullVariants.size() == 2)
    fullVariants.forEach { check(it.asJsonObject.keySet() == setOf("url", "height", "original", "mime")) }
    val galleryMedia = wire.getAsJsonArray("media").single().asJsonObject
    check(
        galleryMedia.keySet() ==
            setOf("url", "localPath", "mime", "progressiveUrls", "fallbackUrls", "videoVariants", "isAnimated", "thumbnailUrl"),
    )
    check(galleryMedia.getAsJsonArray("videoVariants").single().asJsonObject.keySet() == setOf("url", "height", "original", "mime"))
    val restored = requireNotNull(PostStorageCodec.decode(gson.fromJson(json, PostStorageRecord::class.java)))
    check(restored == videoPostFixture())
    check(restored.preview.videoVariants.isEmpty())
}

/** A v2 manifest is reflective Gson over the domain Post, so this phase writes R8's renamed keys for real. */
private fun writeLegacyOfflineManifest(offline: File) {
    val post = videoPostFixture()
    val folder = File(offline, offlineFolderName(post.id)).apply { check(mkdirs()) }
    val media = File(folder, "0.webm").apply { writeText("offline bytes") }
    val stored = post.copy(media = post.media.map { it.copy(localPath = media.absolutePath) })
    File(folder, "manifest.json").writeText(JsonObject().apply {
        addProperty("version", 2)
        add("post", Gson().toJsonTree(stored))
        add("sourcePost", Gson().toJsonTree(post))
        add("owners", JsonArray().apply { add("acceptance") })
        add("mediaBytes", JsonArray().apply { add(media.length()) })
    }.toString())
}

/** The old build's manifest must resolve in this process, then be rewritten with stable keys. */
private suspend fun verifyLegacyOfflineManifest(offline: File) {
    val post = videoPostFixture()
    val found = requireNotNull(OfflineMediaStore(offline).find(post.id))
    check(found.id == post.id && found.media.single().url == post.media.single().url)
    val manifest = JsonParser.parseString(File(offline, "${offlineFolderName(post.id)}/manifest.json").readText()).asJsonObject
    check(manifest.get("version").asInt == 3 && manifest.getAsJsonObject("post").has("sourcePostId"))
}

private fun offlineFolderName(id: PostId) = MessageDigest.getInstance("SHA-256")
    .digest("${id.source.id}:${id.sourcePostId}".toByteArray()).toHexString()

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

// What the Gson store wrote, HTML escapes included.
private const val LEGACY_UPDATE_STATE = """{"ignoredReleaseId":42,"remindLaterReleaseId":42,"remindLaterUntilEpochMs":123456,""" +
    """"pendingPostInstallChangelog":{"releaseId":42,"versionCode":2,"tagName":"v0.0.2","commitShaShort":"abc1234",""" +
    """"releaseName":"Tom \u0026 Jerry\u0027s \u003crelease\u003e","changelogMarkdown":"","changelogSections":[]}}"""

private const val QUERY_MODE = "acceptance"
private const val RESULT_TAG = "TheoriaReleaseData"
private const val RELEASE_ID = 42L
private const val REMIND_UNTIL = 123_456L
