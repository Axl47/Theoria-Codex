package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the persisted bytes that carry source identity, captured while `SourceKey` was still an enum.
 * Opening identity to custom sites must leave built-in output byte-identical. The only planned
 * change is the backup `version` field (B4). On a mismatch the actual output is written under
 * build/golden-actual/ for review; never regenerate these files to make a refactor pass.
 */
class SourceIdentityGoldenTest {
    private val gson = Gson()

    @Test
    fun `default settings file keeps its persisted bytes`() {
        assertGolden("settings-default.json", gson.toJson(SettingsDataStoreFile.fromDomain(AppSettings())))
    }

    @Test
    fun `multi-source settings file keeps its persisted bytes`() {
        assertGolden("settings-multi-source.json", gson.toJson(SettingsDataStoreFile.fromDomain(multiSourceSettings())))
    }

    @Test
    fun `multi-source backup keeps its encoded bytes`() {
        assertGolden("backup-multi-source.json", ProfileBackupCodec().encode(multiSourceBackup()).toString(Charsets.UTF_8))
    }

    @Test
    fun `offline manifest reflection keeps the post's source fields`() {
        // OfflineMediaStore writes manifests with Gson reflection over the domain Post.
        assertGolden("offline-manifest-post.json", gson.toJson(gelbooruPost))
    }

    private fun assertGolden(name: String, actual: String) {
        val expected = javaClass.classLoader.getResource("golden/source-identity/$name")?.readText()
        if (expected != actual) {
            val written = File("build/golden-actual/source-identity/$name").apply {
                parentFile.mkdirs()
                writeText(actual)
            }
            fail("Golden $name ${if (expected == null) "is missing" else "differs"}; actual output: ${written.absolutePath}")
        }
    }
}

private val pixivCreator = CreatorProfile(SourceKey.PIXIV, "Artist", "3", "https://www.pixiv.net/users/3", "user:3")
private val gelbooruCreator = CreatorProfile(SourceKey.GELBOORU, "uploader", "uploader", null, "user:uploader")

private val pixivPost = Post(
    PostId(SourceKey.PIXIV, "42"), ImageRef("https://example.test/p/42.jpg", null, "image/jpeg"), null,
    pageUrl = "https://www.pixiv.net/artworks/42", width = 100, height = 120, canonicalTags = listOf("cat"),
    rawTags = listOf("cat"), authorName = "Artist", createdAtEpochMs = 1L, creatorProfile = pixivCreator, mediaCount = 2,
)
private val gelbooruPost = Post(
    PostId(SourceKey.GELBOORU, "100"), ImageRef("https://example.test/g/100.jpg", null, "image/jpeg"),
    ImageRef("https://example.test/g/100-full.png", null, "image/png"),
    pageUrl = "https://gelbooru.com/index.php?page=post&s=view&id=100", width = 800, height = 600,
    canonicalTags = listOf("cat_ears", "blue_sky"), rawTags = listOf("cat_ears", "blue_sky"), authorName = "uploader",
    createdAtEpochMs = 2L, creatorProfiles = listOf(gelbooruCreator),
)
private val danbooruPost = Post(
    PostId(SourceKey.DANBOORU, "7"), ImageRef("https://example.test/d/7.jpg", null, "image/jpeg"), null,
    pageUrl = "https://danbooru.donmai.us/posts/7", width = 64, height = 64, canonicalTags = listOf("cat_ears"),
    rawTags = listOf("cat_ears"), authorName = null, createdAtEpochMs = 3L,
)
private val rule34Post = Post(
    PostId(SourceKey.RULE34XXX, "9"), ImageRef("https://example.test/r/9.jpg", null, "image/jpeg"), null,
    pageUrl = "https://rule34.xxx/index.php?page=post&s=view&id=9", width = 10, height = 10,
    canonicalTags = listOf("tail"), rawTags = listOf("tail"), authorName = null, createdAtEpochMs = 4L,
)

private fun multiSourceSettings(): AppSettings = RepositoryPolicies.normalizeSettings(
    AppSettings(
        runtime = SourceWeightSettings.updateWeights(
            SourceRuntimeSettings(enabledSources = setOf(SourceKey.DANBOORU, SourceKey.PIXIV, SourceKey.GELBOORU)),
            setOf(SourceKey.PIXIV, SourceKey.RULE34XXX),
            mapOf(SourceKey.PIXIV to 0.7, SourceKey.RULE34XXX to 0.3),
        ),
        favoriteTagsByProfile = mapOf(
            "profile-main" to listOf(
                FavoriteTagEntry(SourceKey.GELBOORU, listOf("cat ears", "fox ears"), "Ears"),
                FavoriteTagEntry(SourceKey.PIXIV, listOf("猫")),
            ),
        ),
        forYouBlacklistByProfile = mapOf("profile-main" to listOf(ForYouBlacklistEntry(SourceKey.DANBOORU, listOf("hidden")))),
        followedCreators = listOf(FollowedCreator(pixivCreator, "follow-1"), FollowedCreator(gelbooruCreator, "follow-2")),
    ),
)

private fun multiSourceBackup(): ProfileBackup {
    val posts = listOf(pixivPost, gelbooruPost, danbooruPost, rule34Post)
    val codex = Codex(
        "legacy-codex", "Favorites", 5L,
        CodexLikesPolicy.normalizeAutomaticTags(
            listOf(
                CodexAutomaticTag(SourceKey.RULE34XXX, "tail"),
                CodexAutomaticTag(SourceKey.GELBOORU, "cat_ears"),
                CodexAutomaticTag(SourceKey.PIXIV, "cat"),
                CodexAutomaticTag(SourceKey.DANBOORU, "cat_ears", groupIndex = 1),
            ),
        ),
    )
    val unified = unifiedSearch()
    return ProfileBackup(
        createdAtEpochMs = 100L,
        settings = multiSourceSettings(),
        library = BackupLibrary(
            codices = listOf(codex),
            items = posts.mapIndexed { index, post -> CodexItem(codex.codexId, post.id, 10L + index) },
            posts = posts,
            likes = listOf(
                LikedPost("profile-main", gelbooruPost.id, 20L, listOf("cat_ears")),
                LikedPost("profile-main", pixivPost.id, 21L, listOf("cat")),
            ),
            watched = listOf(RecentPostEntry(danbooruPost, 30L, ViewerStreamSource.SEARCH, unified.queryHash)),
            searches = listOf(unified),
        ),
        savedSearches = listOf(SavedSearchEntry("pin", "Cats", unified, 40L)),
        readingPositions = listOf(ReadingPosition(pixivPost.id, RecentPostSection.WATCHED, 2, 50L)),
        statistics = LifetimeStatistics(
            watchedPostCount = 4L,
            watchedBySource = mapOf(SourceKey.GELBOORU to 1L, SourceKey.PIXIV to 3L),
            watchedByTag = mapOf(StatisticsTagKey(SourceKey.GELBOORU, "cat_ears") to 2L),
            searchCount = 2L,
            searchesBySource = mapOf(SourceKey.DANBOORU to 1L, SourceKey.PIXIV to 1L),
        ),
    )
}

private fun unifiedSearch(): RecentSearchEntry {
    val terms = listOf(SearchTerm("cat"))
    val query = Query(QueryMode.Unified, terms, emptyList(), SortMode.NEWEST, null, null, listOf(SearchTermGroup(terms)))
    return RecentSearchEntry(
        query = query,
        queryHash = "unified-cats",
        searchedAtEpochMs = 60L,
        sources = listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
        sourceTags = mapOf(SourceKey.GELBOORU to listOf("cat_ears"), SourceKey.PIXIV to listOf("猫")),
    )
}
