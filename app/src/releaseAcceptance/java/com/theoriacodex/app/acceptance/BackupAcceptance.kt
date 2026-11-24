package com.theoriacodex.app.acceptance

import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.BackupLibrary
import com.theoriacodex.data.repository.DataStoreSettingsRepository
import com.theoriacodex.data.repository.FileBackedReadingPositionRepository
import com.theoriacodex.data.repository.FileBackedSavedSearchRepository
import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.ProfileBackup
import com.theoriacodex.data.repository.ProfileBackupCodec
import com.theoriacodex.data.repository.ProfileBackupService
import com.theoriacodex.data.repository.ProfileBackupStore
import com.theoriacodex.data.repository.ProfileRestorePendingException
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.BackupPreview
import com.theoriacodex.data.repository.SeriesImportPreview
import com.theoriacodex.data.repository.SeriesImportResult
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoListing
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteProfile
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Backups, pinned searches, and the restore journal under R8: what this build writes survives a process restart as
 * the same document a Gson build wrote, and the files a Gson build wrote still load. The write phase leaves a restore
 * pending, as a crash during the library import would; a fresh service in the read phase must decode the journal
 * and finish it. [post] is the acceptance video post.
 */
internal suspend fun writeBackups(directory: File, post: Post) {
    check(directory.mkdirs())
    directory.resolve("backup.json").writeBytes(ProfileBackupCodec().encode(acceptanceBackup(post)))
    FileBackedSavedSearchRepository(directory.resolve("pins"), now = { PIN_SAVED_AT }, newId = { PIN_ID })
        .save(PIN_NAME, acceptanceSearch())
    directory.resolve("shipped-pins").apply { check(mkdirs()) }.resolve(PINS_FILE).writeText(SHIPPED_PINS)
    val interrupted = object : ProfileBackupStore {
        override suspend fun snapshot(includeRecents: Boolean): BackupLibrary = error("Restore never snapshots")
        override suspend fun merge(library: BackupLibrary): Int = throw IOException("Process stopped mid-restore")
    }
    val pending = try {
        withRestoreService(directory.resolve("restore"), interrupted) { it.restore(ProfileBackupCodec().encode(acceptanceBackup(post))) }
        false
    } catch (_: ProfileRestorePendingException) {
        true
    }
    check(pending && directory.resolve("restore").resolve(RESTORE_JOURNAL).isFile)
}

internal suspend fun verifyBackups(directory: File, post: Post) {
    val codec = ProfileBackupCodec()
    val written = directory.resolve("backup.json").readText()
    check(Json.parseToJsonElement(written) == shippedBackupWithSeriesTime())
    val expected = codec.decode(codec.encode(acceptanceBackup(post)))
    check(codec.decode(written.toByteArray()) == expected && codec.decode(SHIPPED_BACKUP.toByteArray()) == expected)
    check(expected.library.posts.single() == post && expected.customSources.single().label == "Safe & 'sound'")

    val pins = listOf(SavedSearchEntry(PIN_ID, PIN_NAME, acceptanceSearch(), PIN_SAVED_AT))
    check(FileBackedSavedSearchRepository(directory.resolve("pins")).observeSavedSearches().first() == pins)
    check(Json.parseToJsonElement(directory.resolve("pins").resolve(PINS_FILE).readText()) == Json.parseToJsonElement(SHIPPED_PINS))
    check(FileBackedSavedSearchRepository(directory.resolve("shipped-pins")).observeSavedSearches().first() == pins)

    val restore = directory.resolve("restore")
    val imported = mutableListOf<BackupLibrary>()
    val store = object : ProfileBackupStore {
        override suspend fun snapshot(includeRecents: Boolean): BackupLibrary = error("Restore never snapshots")
        override suspend fun merge(library: BackupLibrary): Int {
            imported += library
            return 1
        }
    }
    check(withRestoreService(restore, store) { it.recoverPendingRestore() })
    check(!restore.resolve(RESTORE_JOURNAL).exists() && imported.single() == expected.library)
    check(FileBackedSavedSearchRepository(restore.resolve("pins")).observeSavedSearches().first() == pins)

    // Mihon-family backups decode through protobuf models, which R8 must keep with their field numbers.
    val mihon = Base64.getDecoder().decode(MIHON_BACKUP)
    val repos = AddedRepos()
    val (preview, series) = withRestoreService(restore, store, repos) { it.preview(mihon) to it.importSeries(mihon) }
    val counts = SeriesImportPreview(manga = 1, anime = 0, chaptersRead = 1, episodesWatched = 0)
    check(preview == BackupPreview.Series(counts, newRepos = 1, missingSources = listOf("Source")))
    check(series == SeriesImportResult(counts, reposAdded = 1) && repos.given == listOf(NewExtensionRepo("https://repo.example", "ab")))
    check(imported.last().series.single().entries.single().read)
}

/** A restore service over durable stores in [directory], as the app builds one, with [store] for the library. */
private suspend fun <T> withRestoreService(
    directory: File,
    store: ProfileBackupStore,
    extensionRepos: ExtensionRepos? = null,
    block: suspend (ProfileBackupService) -> T,
): T =
    withStoreScope { scope ->
        block(
            ProfileBackupService(
                settings = DataStoreSettingsRepository(directory.resolve("settings"), scope).apply { awaitReady() },
                store = store,
                savedSearches = FileBackedSavedSearchRepository(directory.resolve("pins")),
                readingPositions = FileBackedReadingPositionRepository(directory),
                journalFile = directory.resolve(RESTORE_JOURNAL),
                extensionRepos = extensionRepos,
            ),
        )
    }

/** Adds every repo it is given, offline, keeping what it was given. */
private class AddedRepos : ExtensionRepos {
    val given = mutableListOf<NewExtensionRepo>()
    override suspend fun repos() = given.map { ExtensionRepo(it.url, it.url, it.signingKey.orEmpty()) }
    override suspend fun add(repos: List<NewExtensionRepo>): Map<String, String> = emptyMap<String, String>().also { given += repos }
    override suspend fun remove(url: String) = error("unused")
    override suspend fun available(refresh: Boolean): RepoListing = error("unused")
    override suspend fun install(repo: String, packageName: String): String? = error("unused")
    override suspend fun uninstall(id: String) = error("unused")
}

private fun acceptanceSearch() = RecentSearchEntry(
    Query(QueryMode.Unified, listOf(SearchTerm("a & b", SearchFacet.ARTIST, "artist")), emptyList(), SortMode.NEWEST, null, null),
    "acceptance-search", 5L, RecentSearchKind.MULTI_SEARCH, listOf(SourceKey.GELBOORU, SourceKey.PIXIV),
)

/** [SHIPPED_BACKUP] as this build writes it: series time, which began counting later, is present as zero. */
private fun shippedBackupWithSeriesTime(): JsonObject {
    val shipped = Json.parseToJsonElement(SHIPPED_BACKUP).jsonObject
    val statistics = shipped.getValue("statistics").jsonObject + listOf("readingMs", "animeMs").associateWith { JsonPrimitive(0L) }
    return JsonObject(shipped + ("statistics" to JsonObject(statistics)))
}

/** The values behind [SHIPPED_BACKUP]; edit only together with a backup a Gson build wrote. */
private fun acceptanceBackup(post: Post): ProfileBackup {
    val address = (SiteAddress.parse("safebooru.org") as SiteAddressParse.Valid).address
    return ProfileBackup(
        createdAtEpochMs = 100L,
        settings = AppSettings(oldSearchBar = true),
        library = BackupLibrary(
            codices = listOf(Codex("acceptance-codex", "Cats & <dogs>", 2L, emptyList())),
            items = listOf(CodexItem("acceptance-codex", post.id, 3L)),
            posts = listOf(post),
            likes = listOf(LikedPost("profile-main", post.id, 3L, listOf("a&b"))),
        ),
        savedSearches = listOf(SavedSearchEntry(PIN_ID, PIN_NAME, acceptanceSearch(), PIN_SAVED_AT)),
        statistics = LifetimeStatistics(watchedPostCount = 2L, watchedBySource = mapOf(SourceKey.PIXIV to 2L)),
        customSources = listOf(SiteProfile(address.derivedSourceId, address, "Safe & 'sound'", "GELBOORU_02", addedAtEpochMs = 1L)),
    )
}

private const val PIN_ID = "pin"
private const val PIN_NAME = "Pin & <x>"
private const val PIN_SAVED_AT = 5L
private const val PINS_FILE = "pinned_searches.json"
private const val RESTORE_JOURNAL = "pending_profile_restore.json"

// What the Gson codec wrote for acceptanceBackup(videoPostFixture()).
private const val SHIPPED_BACKUP =
    """{"format":"theoria-profile-backup","version":2,"createdAt":100,"settings":{"followedCreators":[],""" +
    """"sourceCatalogVersion":3,"enabledSources":["PIXIV","GELBOORU","AIBOORU","DANBOORU","NHENTAI","HITOMI",""" +
    """"IWARA","RULE34XXX","RULE34PAHEAL","RULE34VIDEO","RULE34GEN"],""" +
    """"sourceWeights":{"PIXIV":0.19685039370078744,"GELBOORU":0.14173228346456695,""" +
    """"RULE34XXX":0.11811023622047245,"RULE34PAHEAL":0.09448818897637797,"NHENTAI":0.07874015748031497,""" +
    """"HITOMI":0.07874015748031497,"IWARA":0.06299212598425198,"RULE34VIDEO":0.055118110236220486,""" +
    """"RULE34GEN":0.055118110236220486,"DANBOORU":0.09448818897637797,"AIBOORU":0.02362204724409449},""" +
    """"sourceWeightsByConfiguration":{},"viewerPlaybackMode":"LOOP","videoQuality":"AUTO",""" +
    """"downloadQuality":"BEST","downloadsOverMetered":true,"downloadsOverRoaming":false,""" +
    """"cacheFullImageOnSave":false,"resolveUnknownAnimatedDurations":false,""" +
    """"invertMultiImageScrollDirection":false,"oldSearchBar":true,"codexLibrarySort":"MANUAL",""" +
    """"instantSeek":false,"viewerAutoRotateVideos":true,"viewerCommentOrder":"OLDEST",""" +
    """"viewerAutomaticTextTranslationEnabled":false,"viewerEnabledOcrLanguages":[],"scenarioPreset":"NORMAL",""" +
    """"recommendationProfiles":[{"profileId":"profile-main","name":"Main"},{"profileId":"profile-alt",""" +
    """"name":"Alt"}],"activeProfileId":"profile-main","forYouBlacklistByProfile":{},""" +
    """"favoriteTagsByProfile":{},"providerHealth":[]},"library":{"codices":[{"id":"acceptance-codex",""" +
    """"name":"Cats & <dogs>","createdAt":2,"automaticTags":[]}],"items":[{"codexId":"acceptance-codex",""" +
    """"source":"IWARA","postId":"video-contract","savedAt":3}],"posts":[{"source":"IWARA",""" +
    """"sourcePostId":"video-contract","previewUrl":"https://example.test/preview.mp4",""" +
    """"previewMime":"video/mp4","previewProgressiveUrls":[],"previewFallbackUrls":[],"previewIsAnimated":true,""" +
    """"fullUrl":"https://example.test/original.mp4","fullMime":"video/mp4","fullProgressiveUrls":[],""" +
    """"fullFallbackUrls":[],"fullVideoVariants":[{"url":"https://example.test/original.mp4","original":true},""" +
    """{"url":"https://example.test/480.mp4","height":480,"original":false,"mime":"video/mp4"}],""" +
    """"fullIsAnimated":false,"pageUrl":"https://example.test/video-contract","width":1920,"height":1080,""" +
    """"canonicalTags":[],"rawTags":[],"media":[{"url":"https://example.test/gallery.webm","mime":"video/webm",""" +
    """"progressiveUrls":[],"fallbackUrls":[],"videoVariants":[{"url":"https://example.test/gallery-720.webm",""" +
    """"height":720,"original":false,"mime":"video/webm"}],"isAnimated":false}],"taxonomy":[],""" +
    """"creatorProfiles":[],"schemaVersion":1}],"likes":[{"profileId":"profile-main","source":"IWARA",""" +
    """"postId":"video-contract","likedAt":3,"tags":["a&b"]}],"watched":[],"searches":[]},""" +
    """"savedSearches":[{"id":"pin","name":"Pin & <x>","savedAt":5,"search":{"queryHash":"acceptance-search",""" +
    """"searchedAt":5,"payload":{"recentSearchSchemaVersion":1,"query":{"schemaVersion":2,"modeType":"unified",""" +
    """"includeTags":["a & b"],"excludeTags":[],"sort":"NEWEST","includeTerms":[{"value":"a & b",""" +
    """"facet":"ARTIST","sourceNamespace":"artist"}],"excludeTerms":[],"includeTermGroups":[[{"value":"a & b",""" +
    """"facet":"ARTIST","sourceNamespace":"artist"}]]},"kind":"MULTI_SEARCH","sources":["GELBOORU","PIXIV"],""" +
    """"sourceTags":{}}}}],"readingPositions":[],"statistics":{"history":{},"codexStatistics":{},""" +
    """"appOpenCount":0,"totalForegroundMs":0,"browsingMs":0,"watchingMs":0,"codexMs":0,"watchedPostCount":2,""" +
    """"watchedBySource":{"PIXIV":2},"watchedByTag":[],"searchCount":0,"searchesBySource":{},""" +
    """"searchesByTag":[],"searchesByTagSeeded":false,"searchesByTypedTag":[],"searchesByTypedTagSeeded":false,""" +
    """"forYouSearchCount":0,"forYouSaveCount":0,"postUrlCopyCount":0,"codexEntryCounts":{},""" +
    """"translatedPhraseCount":0,"translatedSourceCharacterCount":0},""" +
    """"customSources":[{"sourceId":"custom_safebooru_org","baseUrl":"https://safebooru.org",""" +
    """"label":"Safe & 'sound'","family":"GELBOORU_02","credentialsRequired":false,"addedAt":1}]}"""

// What the Gson pinned-search store wrote for the same pin.
private const val SHIPPED_PINS =
    """{"schemaVersion":1,"searches":[{"id":"pin","name":"Pin & <x>","savedAtEpochMs":5,""" +
    """"queryHash":"acceptance-search","searchedAtEpochMs":5,"execution":{"recentSearchSchemaVersion":1,""" +
    """"query":{"schemaVersion":2,"modeType":"unified","includeTags":["a & b"],"excludeTags":[],""" +
    """"sort":"NEWEST","includeTerms":[{"value":"a & b","facet":"ARTIST","sourceNamespace":"artist"}],""" +
    """"excludeTerms":[],"includeTermGroups":[[{"value":"a & b","facet":"ARTIST",""" +
    """"sourceNamespace":"artist"}]]},"kind":"MULTI_SEARCH","sources":["GELBOORU","PIXIV"],"sourceTags":{}}}]}"""

/** A Mihon backup following one manga, `/s` on source 7 (named "Source"), with its one chapter read and one repo. */
private const val MIHON_BACKUP = "H4sIAAAAAAAC/+MS4WAXYtIvlmIMbmLk4GLRL9Y3VGBcxcbFxRacX1qUnCrAfolNiksko6SkoNhKX78otSBfL7UiMbcgJ1WLKTEJAPmDCotAAAAA"
