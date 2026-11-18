package com.theoriacodex.app.acceptance

import android.content.Context
import android.os.Bundle
import android.util.Base64
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
import com.theoriacodex.app.sourceauth.AndroidCredentialEnvelopeStore
import com.theoriacodex.app.sourceauth.AndroidKeystoreAesGcm
import com.theoriacodex.app.sourceauth.CredentialEnvelopeReadResult
import com.theoriacodex.app.sourceauth.CredentialStoreWriteResult
import com.theoriacodex.app.sourceauth.SourceCredentialSnapshot
import com.theoriacodex.app.statistics.StatsShareCodex
import com.theoriacodex.app.statistics.StatsShareFile
import com.theoriacodex.app.statistics.StatsShareOverall
import com.theoriacodex.app.statistics.StatsSharePost
import com.theoriacodex.app.statistics.encodeStatsShare
import com.theoriacodex.app.statistics.parseStatsShare
import com.theoriacodex.app.update.ChangelogSection
import com.theoriacodex.app.update.FileBackedUpdateStateStore
import com.theoriacodex.app.update.PendingPostInstallChangelog
import com.theoriacodex.app.update.UpdateStateSnapshot
import com.theoriacodex.data.android.room.LikedPostEntity
import com.theoriacodex.data.android.room.PostEntity
import com.theoriacodex.data.android.room.RecentSearchEntity
import com.theoriacodex.data.android.room.RoomCodexLikesRepository
import com.theoriacodex.data.android.room.RoomRecentsRepository
import com.theoriacodex.data.android.room.TheoriaRoomDatabase
import com.theoriacodex.data.repository.FileBackedQueryRepository
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.data.repository.RecentSearchKind
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
import com.theoriacodex.domain.source.SiteCredentials
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
                withContext(Dispatchers.IO) {
                    verifyData(File(cacheDir, "release-contract-$token"), phase)
                    verifyCredentials(applicationContext, token, phase)
                    verifyRoomPayloads(applicationContext, "release-contract-$token.db", phase)
                }
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
    val statsFile = File(directory, "stats.json")
    val dataStores = File(directory, "datastore")
    val shippedDataStores = File(directory, "datastore-shipped")
    if (phase == "write") {
        check(directory.mkdirs())
        FileBackedUpdateStateStore(updateFile).update { updateState }
        legacyUpdateFile.writeText(LEGACY_UPDATE_STATE)
        FileBackedQueryRepository(directory).upsertAppliedQuery(QUERY_MODE, acceptanceQuery)
        shareFile.writeText(encodeCodexShareFile("Acceptance & <collection>", listOf(videoPostFixture())))
        statsFile.writeText(encodeStatsShare(statsShare))
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
        verifyStatsShare(statsFile.readText())
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

/** Exported stats files keep readable keys, and a file a Gson build exported still imports. */
private fun verifyStatsShare(json: String) {
    val wire = JsonParser.parseString(json).asJsonObject
    check(wire.keySet() == setOf("format", "version", "name", "codices", "overall"))
    val codex = wire.getAsJsonArray("codices").single().asJsonObject
    check(codex.keySet() == setOf("name", "entries", "browsingMs", "watchingMs", "postOpens", "posts"))
    check(codex.getAsJsonArray("posts").single().asJsonObject.keySet() == setOf("key", "label", "opens", "videoMs", "imageMs"))
    check(wire.getAsJsonObject("overall").keySet() == STATS_OVERALL_KEYS)
    check(parseStatsShare(json) == statsShare)
    check(parseStatsShare(LEGACY_STATS_SHARE) == statsShare)
}

private val statsShare = StatsShareFile(
    format = "theoria-codex-stats",
    version = 1,
    name = "Sam & <Al>'s",
    codices = listOf(StatsShareCodex("Land & <scapes>", 3L, 4L, 5L, 6L, listOf(StatsSharePost("GELBOORU:1", "Post = 1", 7L, 8L, 9L)))),
    overall = StatsShareOverall(10L, 11L, 12L, 13L, 14L, 15L, 16L, 17L),
)

private val STATS_OVERALL_KEYS = setOf(
    "timeInAppMs", "browsingMs", "watchingMs", "codexMs", "appOpens", "postsWatched", "postsSaved", "searches",
)

/**
 * Credential envelopes survive R8 and a process restart: one written by this build, one as the Gson store wrote it,
 * and two corrupt ones that must ask for reconnection. Each case has its own Keystore key, deleted in the read phase.
 */
private suspend fun verifyCredentials(context: Context, token: String, phase: String) {
    fun store(case: String) = AndroidCredentialEnvelopeStore(
        context,
        relativeEnvelopePath = "release-contract-$token/$case.json",
        keyAlias = "release-contract-$token-$case",
    )
    val directory = File(context.noBackupFilesDir, "release-contract-$token")
    if (phase == "write") {
        check(store("current").writeVerified(credentials) == CredentialStoreWriteResult.Success)
        context.writeGsonEnvelope(File(directory, "gson.json"), "release-contract-$token-gson", LEGACY_CREDENTIAL_PAYLOAD)
        context.writeGsonEnvelope(File(directory, "corrupt-payload.json"), "release-contract-$token-corrupt-payload", "{\"schemaVersion\":1,")
        File(directory, "corrupt-envelope.json").writeText("[]")
    } else {
        check(store("current").read() == CredentialEnvelopeReadResult.Success(credentials))
        val wire = JsonParser.parseString(File(directory, "current.json").readText()).asJsonObject
        check(wire.keySet() == setOf("formatVersion", "keyVersion", "iv", "ciphertext"))
        check(store("gson").read() == CredentialEnvelopeReadResult.Success(credentials))
        check(store("corrupt-payload").read() == CredentialEnvelopeReadResult.ReconnectRequired)
        check(store("corrupt-envelope").read() == CredentialEnvelopeReadResult.ReconnectRequired)
        listOf("current", "gson", "corrupt-payload", "corrupt-envelope").forEach { check(store(it).reset()) }
        check(directory.deleteRecursively())
    }
}

/** Writes [payload] in an envelope exactly as the Gson store did, Base64 padding escaped as \u003d. */
private fun Context.writeGsonEnvelope(file: File, alias: String, payload: String) {
    val encrypted = AndroidKeystoreAesGcm(alias, "$packageName|source-credentials|format-1".toByteArray())
        .encrypt(payload.toByteArray())
    fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP).replace("=", "\\u003d")
    file.parentFile?.mkdirs()
    file.writeText(
        """{"formatVersion":1,"keyVersion":1,"iv":"${base64(encrypted.iv)}","ciphertext":"${base64(encrypted.ciphertext)}"}""",
    )
}

private val credentials = SourceCredentialSnapshot(
    pixivAccessToken = "access=token&<1>'",
    pixivRefreshToken = "refresh==",
    pixivExpiresAtEpochMs = 1_800_000_000_000L,
    gelbooruUserId = "1",
    gelbooruApiKey = "gel&key",
    rule34XxxUserId = "2",
    rule34XxxApiKey = "r34=key",
    customSites = mapOf("custom_safebooru_org" to SiteCredentials("42", "safe<key>")),
)

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

/**
 * Room payloads written through kotlinx in one process read back after a restart, beside rows exactly as the Gson
 * encoders wrote them (HTML escapes included).
 */
private suspend fun verifyRoomPayloads(context: Context, databaseName: String, phase: String) {
    val database = TheoriaRoomDatabase.create(context, databaseName)
    try {
        val likes = RoomCodexLikesRepository(database)
        val recents = RoomRecentsRepository(database)
        val gsonRowId = PostId(SourceKey.IWARA, "gson-row")
        if (phase == "write") {
            likes.toggleLikeAndSyncSystemCodex(
                ACCEPTANCE_PROFILE, ProfileLibraryIds.likes(ACCEPTANCE_PROFILE), "Likes", videoPostFixture(), LIKE_TAGS, emptySet(),
            )
            recents.recordSearch(unifiedQuery, "kotlinx-search", RecentSearchKind.MULTI_SEARCH, MULTI_SOURCES, emptyMap())
            database.codexLikesDao().insertPost(PostEntity(gsonRowId.source.id, gsonRowId.sourcePostId, GSON_ROOM_POST))
            database.codexLikesDao().insertLike(LikedPostEntity(GSON_PROFILE, gsonRowId.source.id, gsonRowId.sourcePostId, 1L, GSON_LIKED_TAGS))
            database.recentsDao().upsertSearch(RecentSearchEntity("gson-search", GSON_RECENT_SEARCH, 1L, database.recentsDao().nextSearchSequence()))
            return
        }
        check(likes.getPost(videoPostFixture().id) == videoPostFixture())
        check(likes.observeLikes(ACCEPTANCE_PROFILE).first().single().tags == LIKE_TAGS)
        val payload = JsonParser.parseString(checkNotNull(database.codexLikesDao().post("IWARA", "video-contract")).payloadJson)
        check(payload.asJsonObject.getAsJsonArray("fullVideoVariants").first().asJsonObject.has("original"))
        val searches = recents.observeSearches().first().associateBy { it.queryHash }
        check(searches.getValue("kotlinx-search").let { it.query == unifiedQuery && it.sources == MULTI_SOURCES })
        val gsonRow = checkNotNull(likes.getPost(gsonRowId))
        check(gsonRow.title == "Tom & Jerry's <post>" && gsonRow.canonicalTags == listOf("a&b", "c=d"))
        check(likes.observeLikes(GSON_PROFILE).first().single().tags == listOf("a&b", "it's"))
        check(searches.getValue("gson-search").sourceTags == mapOf(SourceKey.GELBOORU to listOf("rock & roll", "<seed>")))
    } finally {
        database.close()
        if (phase == "read") context.deleteDatabase(databaseName)
    }
}

private val LIKE_TAGS = listOf("tom & jerry", "<x>", "a=b", "it's")
private val MULTI_SOURCES = listOf(SourceKey.GELBOORU, SourceKey.PIXIV)

private val unifiedQuery = Query(
    mode = QueryMode.Unified,
    includeTerms = listOf(SearchTerm("a & b", SearchFacet.ARTIST, "artist")),
    excludeTerms = listOf(SearchTerm("<c>")),
    sort = SortMode.NEWEST,
    dateRange = null,
    minScore = null,
)

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
    check(OfflineMediaStore(offline).find(post.id) == found)
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

// What the Gson exporter wrote for statsShare.
private const val LEGACY_STATS_SHARE = """{"format":"theoria-codex-stats","version":1,"name":"Sam \u0026 \u003cAl\u003e\u0027s",""" +
    """"codices":[{"name":"Land \u0026 \u003cscapes\u003e","entries":3,"browsingMs":4,"watchingMs":5,"postOpens":6,""" +
    """"posts":[{"key":"GELBOORU:1","label":"Post \u003d 1","opens":7,"videoMs":8,"imageMs":9}]}],""" +
    """"overall":{"timeInAppMs":10,"browsingMs":11,"watchingMs":12,"codexMs":13,"appOpens":14,"postsWatched":15,""" +
    """"postsSaved":16,"searches":17}}"""

// What the Gson store encrypted for credentials.
private const val LEGACY_CREDENTIAL_PAYLOAD = """{"schemaVersion":1,"pixivAccessToken":"access\u003dtoken\u0026\u003c1\u003e\u0027",""" +
    """"pixivRefreshToken":"refresh\u003d\u003d","pixivExpiresAtEpochMs":1800000000000,"gelbooruUserId":"1",""" +
    """"gelbooruApiKey":"gel\u0026key","rule34XxxUserId":"2","rule34XxxApiKey":"r34\u003dkey",""" +
    """"customSites":{"custom_safebooru_org":{"user":"42","apiKey":"safe\u003ckey\u003e"}}}"""

private const val GSON_ROOM_POST = """{"source":"IWARA","sourcePostId":"gson-row",""" +
    """"previewUrl":"https://example.test/p.jpg?a\u003d1\u0026b\u003d2","previewProgressiveUrls":[],"previewFallbackUrls":[],""" +
    """"previewIsAnimated":false,"canonicalTags":["a\u0026b","c\u003dd"],"rawTags":["\u003craw\u003e"],"media":[],""" +
    """"title":"Tom \u0026 Jerry\u0027s \u003cpost\u003e","taxonomy":[{"value":"a\u0026b","facet":"TAG"}],""" +
    """"creatorProfiles":[],"schemaVersion":1}"""
private const val GSON_LIKED_TAGS = """["a\u0026b","it\u0027s"]"""
private const val GSON_RECENT_SEARCH = """{"recentSearchSchemaVersion":1,"query":{"schemaVersion":2,"modeType":"unified",""" +
    """"includeTags":["x"],"excludeTags":[],"sort":"TOP","includeTerms":[{"value":"x","facet":"TAG"}],"excludeTerms":[],""" +
    """"includeTermGroups":[[{"value":"x","facet":"TAG"}]]},"kind":"FYP","sources":["GELBOORU","PIXIV"],""" +
    """"sourceTags":{"GELBOORU":["rock \u0026 roll","\u003cseed\u003e"]}}"""
private const val ACCEPTANCE_PROFILE = "profile-main"
private const val GSON_PROFILE = "gson-profile"
private const val QUERY_MODE = "acceptance"
private const val RESULT_TAG = "TheoriaReleaseData"
private const val RELEASE_ID = 42L
private const val REMIND_UNTIL = 123_456L
