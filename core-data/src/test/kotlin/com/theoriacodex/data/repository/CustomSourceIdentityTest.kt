package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.RecentSearchPayloadCodec
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Data keyed by a custom source survives every store, whether or not a profile for it exists:
 * parsing accepts any well-formed custom id, and availability is decided elsewhere.
 */
class CustomSourceIdentityTest {
    private val custom = requireNotNull(SourceKey.parse("custom_safebooru_org"))
    private val creator = CreatorProfile(custom, "uploader", "uploader", null, "user:uploader")
    private val post = Post(
        PostId(custom, "123"), ImageRef("https://safebooru.example/p.jpg", null, "image/jpeg"), null,
        pageUrl = "https://safebooru.example/index.php?page=post&s=view&id=123", width = 1, height = 1,
        canonicalTags = listOf("cat_ears"), rawTags = listOf("cat_ears"), authorName = "uploader",
        createdAtEpochMs = 1L, creatorProfiles = listOf(creator),
    )

    @Test
    fun `post payloads and offline manifests keep a custom source`() {
        assertEquals(post, PostStorageCodec.decode(PostStorageCodec.encode(post)))

        val manifest = Gson().toJson(post)
        assertTrue(manifest.contains("\"source\":\"custom_safebooru_org\""))
        assertEquals(post, Gson().fromJson(manifest, Post::class.java))
    }

    @Test
    fun `settings keep custom opt-ins, weights, saved tags and blacklist entries`() {
        val settings = RepositoryPolicies.normalizeSettings(
            AppSettings(
                runtime = SourceRuntimeSettings(
                    enabledSources = setOf(SourceKey.PIXIV, custom),
                    sourceWeights = mapOf(SourceKey.PIXIV to 0.5, custom to 0.5),
                ),
                favoriteTagsByProfile = mapOf("profile-main" to listOf(FavoriteTagEntry(custom, listOf("cat_ears")))),
                forYouBlacklistByProfile = mapOf("profile-main" to listOf(ForYouBlacklistEntry(custom, listOf("hidden")))),
                followedCreators = listOf(FollowedCreator(creator, "follow")),
            ),
        )

        val restored = SettingsDataStoreFile.fromDomain(settings).toDomain()

        assertEquals(setOf(SourceKey.PIXIV, custom), restored.runtime.enabledSources)
        assertEquals(0.5, restored.runtime.sourceWeights.getValue(custom), 1e-9)
        assertEquals(custom, restored.favoriteTagsByProfile.getValue("profile-main").single().source)
        assertEquals(custom, restored.forYouBlacklistByProfile.getValue("profile-main").single().source)
        assertEquals(creator, restored.followedCreators.single().creator)
    }

    @Test
    fun `recent searches and statistics keep custom sources`() {
        val terms = listOf(SearchTerm("cat_ears"))
        val search = RecentSearchEntry(
            Query(QueryMode.Source(custom), terms, emptyList(), SortMode.NEWEST, null, null, listOf(SearchTermGroup(terms))),
            "custom-search", 2L, sourceTags = mapOf(custom to listOf("cat_ears")),
        )
        val payload = RecentSearchPayloadCodec.decodeJson(RecentSearchPayloadCodec.encodeJson(search))
        assertEquals(listOf(custom), payload.sources)
        assertEquals(mapOf(custom to listOf("cat_ears")), payload.sourceTags)
        assertEquals(QueryMode.Source(custom), payload.query.mode)

        val statistics = LifetimeStatistics(
            watchedBySource = mapOf(custom to 2L),
            watchedByTag = mapOf(StatisticsTagKey(custom, "cat_ears") to 1L),
            searchesBySource = mapOf(custom to 1L),
        )
        val restored = StatisticsStoreRecord.fromDomain(statistics).toDomain()
        assertEquals(statistics.watchedBySource, restored.watchedBySource)
        assertEquals(statistics.watchedByTag, restored.watchedByTag)
        assertEquals(statistics.searchesBySource, restored.searchesBySource)
    }

    @Test
    fun `a backup with custom rows and no site profile restores them`() {
        val codex = Codex("legacy-codex", "Favorites", 1L, listOf(CodexAutomaticTag(custom, "cat_ears")))
        val backup = ProfileBackup(
            createdAtEpochMs = 10L,
            settings = RepositoryPolicies.normalizeSettings(AppSettings()),
            library = BackupLibrary(
                codices = listOf(codex),
                items = listOf(CodexItem(codex.codexId, post.id, 2L)),
                posts = listOf(post),
                likes = listOf(LikedPost("profile-main", post.id, 3L, listOf("cat_ears"))),
            ),
            readingPositions = listOf(ReadingPosition(post.id, RecentPostSection.WATCHED, 1, 4L)),
        )
        val codec = ProfileBackupCodec()

        val decoded = codec.decode(codec.encode(backup))

        assertEquals(listOf(post.id), decoded.library.likes.map(LikedPost::postId))
        assertEquals(custom, decoded.library.codices.single().automaticTags.single().source)
        assertEquals(post.id, decoded.readingPositions.single().postId)
        assertEquals(custom, decoded.library.posts.single().creatorProfiles.single().source)
    }
}
