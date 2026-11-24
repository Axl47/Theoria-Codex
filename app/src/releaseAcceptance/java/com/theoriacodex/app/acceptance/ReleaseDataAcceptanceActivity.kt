package com.theoriacodex.app.acceptance

import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
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
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Installed only in the minified acceptance APK; write/read phases straddle a process restart. */
class ReleaseDataAcceptanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra("token").orEmpty()
        val phase = intent.getStringExtra("phase").orEmpty()
        require(token.matches(Regex("[a-f0-9]{24}")) && phase in setOf("write", "read")) { "bad token or phase: token=$token phase=$phase" }
        val status = TextView(this).also { setContentView(it) }
        status.text = "Checking minified data contracts"
        lifecycleScope.launch {
            runCatchingExceptionsPreservingCancellation {
                withContext(Dispatchers.IO) {
                    verifyData(File(cacheDir, "release-contract-$token"), phase)
                    verifyCredentials(applicationContext, token, phase)
                    verifyRoomPayloads(applicationContext, "release-contract-$token.db", phase)
                    verifyRecordFiles(applicationContext, token, phase)
                }
                status.text = "Minified data contracts passed: $phase"
                Log.i(RESULT_TAG, "PASS:$token:$phase")
            }.onFailure { failure ->
                status.text = "Minified data contracts failed"
                Log.e(RESULT_TAG, "FAIL:$token:$phase:${failure.javaClass.simpleName}: ${failure.message}", failure)
            }
        }
    }
}

private suspend fun verifyData(directory: File, phase: String) {
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
        check(directory.mkdirs()) { "could not create ${directory.path}" }
        writeBackups(File(directory, "backup"), videoPostFixture())
        FileBackedUpdateStateStore(updateFile).update { updateState }
        legacyUpdateFile.writeText(LEGACY_UPDATE_STATE)
        FileBackedQueryRepository(directory).upsertAppliedQuery(QUERY_MODE, acceptanceQuery)
        shareFile.writeText(encodeCodexShareFile("Acceptance & <collection>", listOf(videoPostFixture())))
        statsFile.writeText(encodeStatsShare(statsShare))
        postFile.writeText(DurableJson.encodeToString(PostStorageRecord.serializer(), PostStorageCodec.encode(videoPostFixture())))
        legacyPostFile.writeText(LEGACY_VIDEO_POST)
        saveOfflineCopy(File(directory, "offline"))
        writeDataStores(dataStores, shippedDataStores)
        tagsFile.writeText("""{"sources":{"GELBOORU":[{"text":"legacy","type":"trending","count":9}]}}""")
        val tags = FileBackedTagSuggestionStore(tagsFile, persistenceDebounceMs = 0L)
        try {
            tags.awaitLoaded()
            val legacyTags = tags.get(SourceKey.GELBOORU, 10)
            check(legacyTags.any { it.text == "legacy" }) { "tag store lacked legacy: $legacyTags" }
            tags.put(SourceKey.GELBOORU, listOf(TagSuggestion("fresh", "seen", 10)))
            tags.flush()
        } finally {
            tags.close()
        }
    } else {
        verifyUpdateState(updateFile, legacyUpdateFile)
        verifyBackups(File(directory, "backup"), videoPostFixture())
        verifyQueryStore(directory)
        verifyCodexShare(shareFile.readText())
        verifyStatsShare(statsFile.readText())
        verifyVideoPost(postFile.readText())
        val legacyPost = requireNotNull(PostStorageCodec.decode(DurableJson.decodeFromString(PostStorageRecord.serializer(), legacyPostFile.readText()))) { "legacy post file decoded to null" }
        check(legacyPost.id == PostId(SourceKey.IWARA, "legacy-video")) { "legacy post id was ${legacyPost.id}" }
        check(legacyPost.preview.url == "https://example.test/preview.mp4" && legacyPost.preview.videoVariants.isEmpty()) { "legacy preview was ${legacyPost.preview}" }
        check(legacyPost.full?.url == "https://example.test/full.mp4" && legacyPost.full?.videoVariants == emptyList<VideoVariant>()) { "legacy full was ${legacyPost.full}" }
        check(legacyPost.media.single().url == "https://example.test/gallery.webm" && legacyPost.media.single().videoVariants.isEmpty()) { "legacy media was ${legacyPost.media}" }
        verifyOfflineCopy(File(directory, "offline"))
        verifyDataStores(dataStores, shippedDataStores)
        val tags = FileBackedTagSuggestionStore(tagsFile, persistenceDebounceMs = 0L)
        try {
            tags.awaitLoaded()
            val freshTags = tags.get(SourceKey.GELBOORU, 10)
            check(freshTags.any { it.text == "fresh" }) { "tag store lacked fresh: $freshTags" }
            val tagsJson = tagsFile.readText()
            check(Json.parseToJsonElement(tagsJson).jsonObject.getValue("sources").jsonObject.containsKey("GELBOORU")) { "tags file lacked GELBOORU source: $tagsJson" }
        } finally {
            tags.close()
        }
        check(directory.deleteRecursively()) { "could not delete ${directory.path}" }
    }
}

/** kotlinx serializers survive R8 with readable keys, and a file the Gson store wrote still loads. */
private suspend fun verifyUpdateState(updateFile: File, legacyUpdateFile: File) {
    val snapshot = FileBackedUpdateStateStore(updateFile).snapshot()
    check(snapshot == updateState) { "update state snapshot was $snapshot" }
    val wire = Json.parseToJsonElement(updateFile.readText()).jsonObject
    check(wire.keys == UPDATE_STATE_KEYS) { "update.json keys were ${wire.keys}" }
    val changelog = wire.getValue("pendingPostInstallChangelog").jsonObject
    check(changelog.keys == CHANGELOG_KEYS) { "changelog keys were ${changelog.keys}" }
    check(changelog.getValue("changelogSections").jsonArray.single().jsonObject.keys == setOf("title", "bullets")) { "changelog section keys were ${changelog.getValue("changelogSections").jsonArray.single().jsonObject.keys}" }
    val legacy = FileBackedUpdateStateStore(legacyUpdateFile).snapshot()
    check(legacy.ignoredReleaseId == RELEASE_ID && legacy.remindLaterUntilEpochMs == REMIND_UNTIL) { "legacy update state was $legacy" }
    check(legacy.pendingPostInstallChangelog?.releaseName == "Tom & Jerry's <release>") { "legacy pending changelog was ${legacy.pendingPostInstallChangelog}" }
}

private suspend fun verifyQueryStore(directory: File) {
    val appliedQuery = FileBackedQueryRepository(directory).observeAppliedQuery(QUERY_MODE).first()
    check(appliedQuery == acceptanceQuery) { "applied query was $appliedQuery" }
    val wire = Json.parseToJsonElement(File(directory, "query_store.json").readText()).jsonObject
    val record = wire.getValue("queries").jsonObject.getValue(QUERY_MODE).jsonObject
    check(record.keys == QUERY_KEYS) { "query keys were ${record.keys}" }
    check(record.getValue("includeTerms").jsonArray.first().jsonObject.keys == setOf("value", "facet", "sourceNamespace")) { "include term keys were ${record.getValue("includeTerms").jsonArray.first().jsonObject.keys}" }
}

private fun verifyCodexShare(json: String) {
    val wire = Json.parseToJsonElement(json).jsonObject
    check(wire.keys == setOf("version", "title", "posts")) { "share wire keys were ${wire.keys}" }
    check(wire.getValue("posts").jsonArray.single().jsonObject.keys == setOf("source", "sourcePostId", "snapshot")) { "share post keys were ${wire.getValue("posts").jsonArray.single().jsonObject.keys}" }
    val imported = requireNotNull(parseCodexShareFile(json)) { "codex share file parsed to null" }
    check(imported.title == "Acceptance & <collection>") { "imported share title was ${imported.title}" }
    val snapshotId = codexSharePostSnapshot(imported.posts.orEmpty().single())?.id
    check(snapshotId == videoPostFixture().id) { "imported share snapshot id was $snapshotId" }
}

/** Exported stats files keep readable keys, and a file a Gson build exported still imports. */
private fun verifyStatsShare(json: String) {
    val wire = Json.parseToJsonElement(json).jsonObject
    check(wire.keys == setOf("format", "version", "name", "codices", "overall")) { "stats wire keys were ${wire.keys}" }
    val codex = wire.getValue("codices").jsonArray.single().jsonObject
    check(codex.keys == setOf("name", "entries", "browsingMs", "watchingMs", "postOpens", "posts")) { "stats codex keys were ${codex.keys}" }
    check(codex.getValue("posts").jsonArray.single().jsonObject.keys == setOf("key", "label", "opens", "videoMs", "imageMs")) { "stats post keys were ${codex.getValue("posts").jsonArray.single().jsonObject.keys}" }
    check(wire.getValue("overall").jsonObject.keys == STATS_OVERALL_KEYS) { "stats overall keys were ${wire.getValue("overall").jsonObject.keys}" }
    val parsed = parseStatsShare(json)
    check(parsed == statsShare) { "parsed stats share was $parsed" }
    // The Gson export predates series time, which reads as zero.
    val parsedLegacy = parseStatsShare(LEGACY_STATS_SHARE)
    check(parsedLegacy == statsShare.copy(overall = statsShare.overall?.copy(readingMs = 0L, animeMs = 0L))) { "parsed legacy stats share was $parsedLegacy" }
}

private val statsShare = StatsShareFile(
    format = "theoria-codex-stats",
    version = 1,
    name = "Sam & <Al>'s",
    codices = listOf(StatsShareCodex("Land & <scapes>", 3L, 4L, 5L, 6L, listOf(StatsSharePost("GELBOORU:1", "Post = 1", 7L, 8L, 9L)))),
    overall = StatsShareOverall(
        timeInAppMs = 10L, browsingMs = 11L, watchingMs = 12L, codexMs = 13L, readingMs = 18L, animeMs = 19L,
        appOpens = 14L, postsWatched = 15L, postsSaved = 16L, searches = 17L,
    ),
)

private val STATS_OVERALL_KEYS = setOf(
    "timeInAppMs", "browsingMs", "watchingMs", "codexMs", "readingMs", "animeMs", "appOpens", "postsWatched", "postsSaved",
    "searches",
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
        check(store("current").writeVerified(credentials) == CredentialStoreWriteResult.Success) { "current credential write did not succeed" }
        context.writeGsonEnvelope(File(directory, "gson.json"), "release-contract-$token-gson", LEGACY_CREDENTIAL_PAYLOAD)
        context.writeGsonEnvelope(File(directory, "corrupt-payload.json"), "release-contract-$token-corrupt-payload", "{\"schemaVersion\":1,")
        File(directory, "corrupt-envelope.json").writeText("[]")
    } else {
        val currentRead = store("current").read()
        check(currentRead == CredentialEnvelopeReadResult.Success(credentials)) { "current credential read was $currentRead" }
        val wire = Json.parseToJsonElement(File(directory, "current.json").readText()).jsonObject
        check(wire.keys == setOf("formatVersion", "keyVersion", "iv", "ciphertext")) { "credential envelope keys were ${wire.keys}" }
        val gsonRead = store("gson").read()
        check(gsonRead == CredentialEnvelopeReadResult.Success(credentials)) { "gson credential read was $gsonRead" }
        val corruptPayloadRead = store("corrupt-payload").read()
        check(corruptPayloadRead == CredentialEnvelopeReadResult.ReconnectRequired) { "corrupt-payload credential read was $corruptPayloadRead" }
        val corruptEnvelopeRead = store("corrupt-envelope").read()
        check(corruptEnvelopeRead == CredentialEnvelopeReadResult.ReconnectRequired) { "corrupt-envelope credential read was $corruptEnvelopeRead" }
        listOf("current", "gson", "corrupt-payload", "corrupt-envelope").forEach { check(store(it).reset()) { "could not reset credential store $it" } }
        check(directory.deleteRecursively()) { "could not delete ${directory.path}" }
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
            recents.recordSearch(unifiedQuery, "kotlinx-search", RecentSearchKind.MULTI_SEARCH, MULTI_SOURCES)
            database.codexLikesDao().insertPost(PostEntity(gsonRowId.source.id, gsonRowId.sourcePostId, GSON_ROOM_POST))
            database.codexLikesDao().insertLike(LikedPostEntity(GSON_PROFILE, gsonRowId.source.id, gsonRowId.sourcePostId, 1L, GSON_LIKED_TAGS))
            database.recentsDao().upsertSearch(RecentSearchEntity("gson-search", GSON_RECENT_SEARCH, 1L, database.recentsDao().nextSearchSequence()))
            return
        }
        val likedPost = likes.getPost(videoPostFixture().id)
        check(likedPost == videoPostFixture()) { "liked post was $likedPost" }
        val likedTags = likes.observeLikes(ACCEPTANCE_PROFILE).first().single().tags
        check(likedTags == LIKE_TAGS) { "liked tags were $likedTags" }
        val payload = Json.parseToJsonElement(checkNotNull(database.codexLikesDao().post("IWARA", "video-contract")) { "liked video post row missing" }.payloadJson)
        check(payload.jsonObject.getValue("fullVideoVariants").jsonArray.first().jsonObject.containsKey("original")) { "liked payload lacked original variant: $payload" }
        val searches = recents.observeSearches().first().associateBy { it.queryHash }
        check(searches.getValue("kotlinx-search").let { it.query == unifiedQuery && it.sources == MULTI_SOURCES }) { "kotlinx-search was ${searches.getValue("kotlinx-search")}" }
        val gsonRow = checkNotNull(likes.getPost(gsonRowId)) { "gson like row $gsonRowId missing" }
        check(gsonRow.title == "Tom & Jerry's <post>" && gsonRow.canonicalTags == listOf("a&b", "c=d")) { "gson row title ${gsonRow.title} tags ${gsonRow.canonicalTags}" }
        val gsonTags = likes.observeLikes(GSON_PROFILE).first().single().tags
        check(gsonTags == listOf("a&b", "it's")) { "gson like tags were $gsonTags" }
        // A For You row reads as Unified with an entry of tags for every source it searched, so it never fails a read.
        check(searches.getValue("gson-search").let {
            it.kind == RecentSearchKind.UNIFIED &&
                it.sourceTags == mapOf(SourceKey.GELBOORU to listOf("rock & roll", "<seed>"), SourceKey.PIXIV to emptyList())
        }) { "gson-search was ${searches.getValue("gson-search")}" }
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

/** Null values are omitted, as the Gson store omitted them. */
private fun verifyVideoPost(json: String) {
    val wire = Json.parseToJsonElement(json).jsonObject
    val fullVariants = wire.getValue("fullVideoVariants").jsonArray.map { it.jsonObject.keys }
    check(fullVariants == listOf(setOf("url", "original"), setOf("url", "height", "original", "mime"))) { "full video variants were $fullVariants" }
    val galleryMedia = wire.getValue("media").jsonArray.single().jsonObject
    check(galleryMedia.keys == setOf("url", "mime", "progressiveUrls", "fallbackUrls", "videoVariants", "isAnimated")) { "gallery media keys were ${galleryMedia.keys}" }
    check(galleryMedia.getValue("videoVariants").jsonArray.single().jsonObject.keys == setOf("url", "height", "original", "mime")) { "gallery variant keys were ${galleryMedia.getValue("videoVariants").jsonArray.single().jsonObject.keys}" }
    val restored = requireNotNull(PostStorageCodec.decode(DurableJson.decodeFromString(PostStorageRecord.serializer(), json))) { "post storage record decoded to null" }
    check(restored == videoPostFixture()) { "restored post was $restored" }
    check(restored.preview.videoVariants.isEmpty()) { "restored preview variants were ${restored.preview.videoVariants}" }
}

/** The manifest's kotlinx serializer must survive R8: written in one process, read with stable keys in the next. */
private suspend fun saveOfflineCopy(offline: File) {
    val post = videoPostFixture()
    OfflineMediaStore(offline).save(post, post.media, "acceptance") { _, file -> file.writeText("offline bytes"); null }
}

private suspend fun verifyOfflineCopy(offline: File) {
    val post = videoPostFixture()
    val manifest = Json.parseToJsonElement(File(offline, "${offlineFolderName(post.id)}/manifest.json").readText()).jsonObject
    check(manifest.keys == setOf("version", "post", "sourcePost", "owners", "mediaBytes")) { "offline manifest keys were ${manifest.keys}" }
    check(manifest.getValue("version").jsonPrimitive.int == 3) { "offline manifest version was ${manifest.getValue("version")}" }
    val sourcePost = manifest.getValue("sourcePost").jsonObject
    check(sourcePost.containsKey("fullVideoVariants")) { "offline manifest sourcePost keys were ${sourcePost.keys}" }
    val store = OfflineMediaStore(offline)
    val found = requireNotNull(store.find(post.id)) { "offline store lacked ${post.id}" }
    val offlineText = found.media.single().localPath?.let(::File)?.readText()
    check(offlineText == "offline bytes") { "offline media text was $offlineText" }
    val stripped = store.withoutOfflineLocations(found)
    check(stripped == post) { "offline store without locations was $stripped" }
    val postsByOwner = store.snapshot.value?.postsByOwner
    check(postsByOwner == mapOf("acceptance" to setOf(post.id))) { "offline posts by owner were $postsByOwner" }
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
