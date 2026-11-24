package com.theoriacodex.app.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.app.codex.encodeCodexShareFile
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.sourceauth.CredentialPayloadRecord
import com.theoriacodex.app.sourceauth.SourceCredentialSnapshot
import com.theoriacodex.app.statistics.StatsShareCodex
import com.theoriacodex.app.statistics.StatsShareFile
import com.theoriacodex.app.statistics.StatsShareOverall
import com.theoriacodex.app.statistics.StatsSharePost
import com.theoriacodex.app.update.ChangelogSection
import com.theoriacodex.app.update.PendingPostInstallChangelog
import com.theoriacodex.app.update.UpdateStateSnapshot
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.BackupLibrary
import com.theoriacodex.data.repository.DATASTORE_SETTINGS_FILE_NAME
import com.theoriacodex.data.repository.DATASTORE_STATISTICS_FILE_NAME
import com.theoriacodex.data.repository.DATASTORE_UI_RESTORE_FILE_NAME
import com.theoriacodex.data.repository.DataStoreSettingsRepository
import com.theoriacodex.data.repository.DataStoreStatisticsRepository
import com.theoriacodex.data.repository.DataStoreUiRestoreRepository
import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.data.repository.ProfileBackup
import com.theoriacodex.data.repository.ProfileBackupCodec
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.ImageRefStorageRecord
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.data.storage.RecentSearchPayload
import com.theoriacodex.data.storage.RecentSearchPayloadCodec
import com.theoriacodex.data.storage.VideoVariantRecord
import com.theoriacodex.data.storage.withDataStoreScope
import com.theoriacodex.domain.adapter.TagSuggestion
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
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.domain.source.SiteProfile
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstalledJsonCompatibilityDeviceTest {
    @Test
    fun stableWireKeysSurviveTheInstalledTargetApk() {
        // Stored posts and backups share this record graph; its generated serializers must keep every key.
        assertSerialNames(
            PostStorageRecord.serializer(),
            "source",
            "sourcePostId",
            "previewUrl",
            "previewLocalPath",
            "previewMime",
            "previewProgressiveUrls",
            "previewFallbackUrls",
            "previewIsAnimated",
            "fullUrl",
            "fullLocalPath",
            "fullMime",
            "fullProgressiveUrls",
            "fullFallbackUrls",
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
            "galleryLengthUnknown",
            "taxonomy",
            "creatorProfiles",
            "contentHash",
            "schemaVersion",
        )
        assertSerialNames(
            ImageRefStorageRecord.serializer(),
            "url", "localPath", "mime", "progressiveUrls", "fallbackUrls", "videoVariants", "isAnimated", "thumbnailUrl",
        )
        assertSerialNames(VideoVariantRecord.serializer(), "url", "height", "original", "mime")
        val changelog = PendingPostInstallChangelog(
            releaseId = 7L,
            fromVersionCode = 6,
            versionCode = 8,
            tagName = "v0.0.8",
            commitShaShort = "abcdef0",
            releaseName = "Stable",
            changelogSections = listOf(ChangelogSection("Fixes", listOf("Stable JSON"))),
        )
        assertKotlinxJsonKeys(
            UpdateStateSnapshot.serializer(),
            UpdateStateSnapshot(1L, 2L, 3, 4L, 5L, 6L, changelog, changelog),
            "lastSeenReleaseId",
            "pendingInstallReleaseId",
            "pendingInstallVersionCode",
            "ignoredReleaseId",
            "remindLaterReleaseId",
            "remindLaterUntilEpochMs",
            "pendingPostInstallChangelog",
            "lastInstalledChangelog",
        )
        assertEquals(
            setOf("version", "title", "posts"),
            JSONObject(encodeCodexShareFile("Shared", emptyList())).keySetCompat(),
        )
        assertKotlinxJsonKeys(
            CredentialPayloadRecord.serializer(),
            CredentialPayloadRecord(
                SourceCredentialSnapshot("a", "r", 1L, "1", "g", "2", "x", mapOf("custom_a_test" to SiteCredentials("u", "k"))),
            ),
            "schemaVersion",
            "pixivAccessToken",
            "pixivRefreshToken",
            "pixivExpiresAtEpochMs",
            "gelbooruUserId",
            "gelbooruApiKey",
            "rule34XxxUserId",
            "rule34XxxApiKey",
            "customSites",
        )
        assertKotlinxJsonKeys(
            StatsShareFile.serializer(),
            StatsShareFile(
                "theoria-codex-stats", 1, "n", listOf(StatsShareCodex("c", posts = listOf(StatsSharePost("k")))), StatsShareOverall(),
            ),
            "format",
            "version",
            "name",
            "codices",
            "overall",
        )
    }

    @Test
    fun dataStoreFilesKeepStableKeysInTheInstalledApk() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "release-json-test/datastore").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            withDataStoreScope { scope ->
                DataStoreSettingsRepository(directory, scope).updateSettings { it.copy(oldSearchBar = true) }
                DataStoreUiRestoreRepository(directory, scope).setSearchScrollState("h", SearchScrollState(7, 12))
                DataStoreStatisticsRepository(directory, scope).recordAppOpen()
                fun keys(name: String) = JSONObject(directory.resolve(name).readText())
                assertEquals(setOf("schemaVersion", "settings"), keys(DATASTORE_SETTINGS_FILE_NAME).keySetCompat())
                assertEquals(true, keys(DATASTORE_SETTINGS_FILE_NAME).getJSONObject("settings").getBoolean("oldSearchBar"))
                val state = keys(DATASTORE_UI_RESTORE_FILE_NAME).getJSONObject("state")
                assertEquals(
                    setOf("firstVisibleItemIndex", "firstVisibleItemOffsetPx"),
                    state.getJSONObject("searchScrollStates").getJSONObject("h").keySetCompat(),
                )
                assertEquals(1L, keys(DATASTORE_STATISTICS_FILE_NAME).getJSONObject("statistics").getLong("appOpenCount"))
            }
        } finally {
            directory.deleteRecursively()
        }
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

        val encoded = DurableJson.encodeToString(PostStorageRecord.serializer(), PostStorageCodec.encode(post))
        val wire = JSONObject(encoded)
        assertVariantWire(wire.getJSONArray("fullVideoVariants").getJSONObject(0), fullVariants[0])
        assertVariantWire(wire.getJSONArray("fullVideoVariants").getJSONObject(1), fullVariants[1])
        assertVariantWire(wire.getJSONArray("media").getJSONObject(0).getJSONArray("videoVariants").getJSONObject(0), galleryVariants[0])
        assertEquals(post.preview.url, wire.getString("previewUrl"))
        val restored = requireNotNull(PostStorageCodec.decode(DurableJson.decodeFromString(PostStorageRecord.serializer(), encoded)))
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
        val restored = requireNotNull(PostStorageCodec.decode(DurableJson.decodeFromString(PostStorageRecord.serializer(), legacy)))
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

    private fun assertSerialNames(serializer: KSerializer<*>, vararg keys: String) {
        val descriptor = serializer.descriptor
        assertEquals(keys.toSet(), (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet())
    }

    @Test
    fun backupsRoundTripThroughTheInstalledCodec() {
        val codec = ProfileBackupCodec()
        val address = (SiteAddress.parse("safebooru.org") as SiteAddressParse.Valid).address
        val site = SiteProfile(address.derivedSourceId, address, "Safe & 'sound'", "GELBOORU_02", addedAtEpochMs = 1L)
        val post = Post(
            id = PostId(SourceKey.IWARA, "backup-contract"),
            preview = ImageRef("https://example.test/preview.mp4", null, "video/mp4", isAnimated = true),
            full = ImageRef("https://example.test/full.mp4", null, "video/mp4", videoVariants = listOf(
                VideoVariant("https://example.test/480.mp4", height = 480, mime = "video/mp4"),
            )),
            pageUrl = "https://example.test/backup-contract",
            width = 1920,
            height = 1080,
            canonicalTags = listOf("a&b"),
            rawTags = listOf("a&b"),
            authorName = null,
            createdAtEpochMs = 1L,
        )
        val backup = ProfileBackup(
            createdAtEpochMs = 1L,
            settings = AppSettings(oldSearchBar = true),
            library = BackupLibrary(emptyList(), emptyList(), listOf(post), emptyList()),
            statistics = LifetimeStatistics(watchedPostCount = 2L),
            customSources = listOf(site),
        )

        val bytes = codec.encode(backup)
        val wire = JSONObject(bytes.decodeToString())
        assertEquals(
            setOf("format", "version", "createdAt", "settings", "library", "savedSearches", "readingPositions", "statistics", "customSources"),
            wire.keySetCompat(),
        )
        assertEquals(true, wire.getJSONObject("settings").getBoolean("oldSearchBar"))
        assertEquals(2L, wire.getJSONObject("statistics").getLong("watchedPostCount"))
        assertEquals("custom_safebooru_org", wire.getJSONArray("customSources").getJSONObject(0).getString("sourceId"))
        val restored = codec.decode(bytes)
        assertEquals(post, restored.library.posts.single())
        assertEquals(true, restored.settings.oldSearchBar)
        assertEquals(2L, restored.statistics?.watchedPostCount)
        assertEquals(listOf(site), restored.customSources)
    }

    private fun <T> assertKotlinxJsonKeys(serializer: KSerializer<T>, value: T, vararg keys: String) {
        assertEquals(keys.toSet(), JSONObject(DurableJson.encodeToString(serializer, value)).keySetCompat())
    }

    @Test
    fun recentSearchPayloadsRoundTripAndGsonRowsStillDecode() {
        val query = Query(
            mode = QueryMode.Unified,
            includeTerms = listOf(SearchTerm("a & b", SearchFacet.ARTIST, "artist")),
            excludeTerms = listOf(SearchTerm("<c>")),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
        val sources = listOf(SourceKey.GELBOORU, SourceKey.PIXIV)
        val encoded = RecentSearchPayloadCodec.encodeJson(query, RecentSearchKind.MULTI_SEARCH, sources)
        assertEquals(
            setOf("recentSearchSchemaVersion", "query", "kind", "sources", "sourceTags"),
            JSONObject(encoded).keySetCompat(),
        )
        assertEquals(RecentSearchPayload(query, RecentSearchKind.MULTI_SEARCH, sources), RecentSearchPayloadCodec.decodeJson(encoded))
        val gsonRow = """{"recentSearchSchemaVersion":1,"query":{"schemaVersion":2,"modeType":"unified",""" +
            """"includeTags":["x"],"excludeTags":[],"sort":"TOP"},"kind":"FYP","sources":["PIXIV"],""" +
            """"sourceTags":{"PIXIV":["rock \u0026 roll"]}}"""
        // A For You row from before the shelves page reads as Unified with its tags, for the saved-search conversion.
        val retired = RecentSearchPayloadCodec.decodeJson(gsonRow)
        assertEquals(RecentSearchKind.UNIFIED, retired.kind)
        assertEquals(mapOf(SourceKey.PIXIV to listOf("rock & roll")), retired.sourceTags)
    }

    private fun assertVariantWire(wire: JSONObject, expected: VideoVariant) {
        // Null values are omitted, as Gson omitted them.
        val present = setOfNotNull("height".takeIf { expected.height != null }, "mime".takeIf { expected.mime != null })
        assertEquals(setOf("url", "original") + present, wire.keySetCompat())
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
