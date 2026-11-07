package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.SiteProfile

const val PROFILE_BACKUP_MAX_BYTES = 32 * 1024 * 1024

/** 2 added the optional `customSources` list; older apps refuse it with their "newer version" message. */
private const val PROFILE_BACKUP_VERSION = 2

/** A sanity bound; a device allows [SiteProfile.MAX_CUSTOM_SITES] plus any added during a restore. */
private const val MAX_BACKUP_SITE_PROFILES = 256

/** Versioned, bounded metadata document; no reflected backup-specific models or device files. */
class ProfileBackupCodec(private val gson: Gson = Gson()) {
    fun encode(backup: ProfileBackup): ByteArray {
        validate(backup)
        return backupObject {
            addProperty("format", "theoria-profile-backup")
            addProperty("version", PROFILE_BACKUP_VERSION)
            addProperty("createdAt", backup.createdAtEpochMs)
            add("settings", gson.toJsonTree(LegacySettingsStoreRecord.fromDomain(portableSettings(backup.settings))))
            add("library", encodeLibrary(backup.library))
            add("savedSearches", backupArray(backup.savedSearches) { entry -> backupObject {
                addProperty("id", entry.id)
                addProperty("name", entry.name)
                addProperty("savedAt", entry.savedAtEpochMs)
                add("search", backupSearch(entry.search, gson))
            } })
            add("readingPositions", backupArray(backup.readingPositions) { entry -> backupObject {
                putId(entry.postId)
                addProperty("section", entry.section.name)
                addProperty("mediaNumber", entry.mediaNumber)
                addProperty("lastViewedAt", entry.lastViewedAt)
            } })
            backup.statistics?.let { add("statistics", gson.toJsonTree(StatisticsStoreRecord.fromDomain(it))) }
            if (backup.customSources.isNotEmpty()) {
                add("customSources", backupArray(backup.customSources) { gson.toJsonTree(SiteProfileRecord.fromDomain(it)) })
            }
        }.toString().toByteArray(Charsets.UTF_8).also {
            require(it.size <= PROFILE_BACKUP_MAX_BYTES) { "Backup exceeds the 32 MiB metadata limit" }
        }
    }

    fun decode(bytes: ByteArray): ProfileBackup {
        require(bytes.isNotEmpty() && bytes.size <= PROFILE_BACKUP_MAX_BYTES) { "Backup must be at most 32 MiB" }
        val raw = decodeDocumentUtf8(bytes)
        checkDocumentDepth(raw)
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.backupString("format") == "theoria-profile-backup") { "This is not a Theoria profile backup" }
        require(root.backupInt("version") in 1..PROFILE_BACKUP_VERSION) { "This backup needs a newer version of Theoria" }
        return ProfileBackup(
            createdAtEpochMs = root.backupLong("createdAt"),
            settings = decodeSettings(requireNotNull(root.getAsJsonObject("settings"))),
            library = decodeLibrary(requireNotNull(root.getAsJsonObject("library"))),
            savedSearches = root.backupObjects("savedSearches", MAX_SAVED_SEARCHES).map { entry ->
                SavedSearchEntry(entry.backupString("id"), entry.backupString("name"),
                    entry.getAsJsonObject("search").readBackupSearch(gson), entry.backupLong("savedAt"))
            },
            readingPositions = root.backupObjects("readingPositions", MAX_READING_POSITIONS).map { entry ->
                ReadingPosition(entry.backupId(), RecentPostSection.valueOf(entry.backupString("section")),
                    entry.backupInt("mediaNumber"), entry.backupLong("lastViewedAt"))
            },
            statistics = root.getAsJsonObject("statistics")?.let { record ->
                gson.fromJson(record, StatisticsStoreRecord::class.java).also(StatisticsStoreRecord::validate).toDomain()
            },
            customSources = decodeCustomSources(root),
        ).also(::validate)
    }

    /** Strict, like the rest of the codec: one invalid profile rejects the backup before anything is written. */
    private fun decodeCustomSources(root: JsonObject): List<SiteProfile> {
        if (!root.has("customSources")) return emptyList()
        return root.backupObjects("customSources", MAX_BACKUP_SITE_PROFILES).map { record ->
            requireNotNull(gson.fromJson(record, SiteProfileRecord::class.java).toDomainOrNull()) {
                "Backup contains an invalid site"
            }
        }
    }

    private fun encodeLibrary(library: BackupLibrary): JsonObject = backupObject {
        add("codices", backupArray(library.codices) { codex -> backupObject {
            addProperty("id", codex.codexId)
            addProperty("name", codex.name)
            addProperty("createdAt", codex.createdAtEpochMs)
            add("automaticTags", backupArray(codex.automaticTags) { tag -> backupObject {
                addProperty("source", tag.source.id)
                addProperty("tag", tag.tag)
                addProperty("group", tag.groupIndex)
            } })
        } })
        add("items", backupArray(library.items) { item -> backupObject {
            addProperty("codexId", item.codexId)
            putId(item.postId)
            addProperty("savedAt", item.savedAtEpochMs)
        } })
        add("posts", backupArray(library.posts) { encodeBackupPost(it, gson) })
        add("likes", backupArray(library.likes) { like -> backupObject {
            addProperty("profileId", like.profileId)
            putId(like.postId)
            addProperty("likedAt", like.likedAtEpochMs)
            add("tags", backupArray(like.tags, ::JsonPrimitive))
        } })
        add("watched", backupArray(library.watched) { entry -> backupObject {
            putId(entry.post.id)
            addProperty("viewedAt", entry.viewedAtEpochMs)
            addProperty("origin", entry.origin.name)
            addProperty("queryHash", entry.originQueryHash)
            addProperty("section", entry.section.name)
            addProperty("highestMedia", entry.maxViewedMediaNumber)
        } })
        add("searches", backupArray(library.searches) { backupSearch(it, gson) })
    }

    private fun decodeLibrary(root: JsonObject): BackupLibrary {
        val posts = root.backupObjects("posts").map { decodeBackupPost(it, gson) }
        val postsById = posts.associateBy { it.id }
        return BackupLibrary(
            codices = root.backupObjects("codices", 10_000).map { codex ->
                Codex(codex.backupString("id"), codex.backupString("name"), codex.backupLong("createdAt"),
                    codex.backupObjects("automaticTags", 10_000).map { tag ->
                        CodexAutomaticTag(SourceKey.parseOrThrow(tag.backupString("source")),
                            tag.backupString("tag"), tag.backupInt("group"))
                    })
            },
            items = root.backupObjects("items").map {
                CodexItem(it.backupString("codexId"), it.backupId(), it.backupLong("savedAt"))
            },
            posts = posts,
            likes = root.backupObjects("likes").map {
                LikedPost(it.backupString("profileId"), it.backupId(), it.backupLong("likedAt"), it.backupStrings("tags"))
            },
            watched = root.backupObjects("watched", 2_000).map {
                RecentPostEntry(requireNotNull(postsById[it.backupId()]) { "Recent post missing from backup" },
                    it.backupLong("viewedAt"), ViewerStreamSource.valueOf(it.backupString("origin")),
                    it.get("queryHash")?.takeUnless { value -> value.isJsonNull }?.asString,
                    RecentPostSection.valueOf(it.backupString("section")), it.backupInt("highestMedia"))
            },
            searches = root.backupObjects("searches", 2_000).map { it.readBackupSearch(gson) },
        )
    }

    private fun decodeSettings(root: JsonObject): AppSettings {
        val profiles = root.backupObjects("recommendationProfiles", 1_000)
        require(profiles.isNotEmpty()) { "Backup has no profiles" }
        profiles.forEach { it.backupString("profileId"); it.backupString("name") }
        validateBackupSettings(root, profiles.map { it.backupString("profileId") }.toSet())
        val settings = gson.fromJson(root, LegacySettingsStoreRecord::class.java).toDomain()
        require(settings.recommendationProfiles.size == profiles.size) { "Backup contains duplicate profiles" }
        require(root.backupString("activeProfileId") in settings.recommendationProfiles.map { it.profileId })
        require(root.backupObjects("followedCreators", MAX_FOLLOWED_CREATORS).size == settings.followedCreators.size) {
            "Backup contains invalid or duplicate followed creators"
        }
        return portableSettings(settings)
    }

    private fun portableSettings(settings: AppSettings): AppSettings = settings.copy(
        followedCreators = settings.followedCreators.map { it.copy(creator = portableBackupCreator(it.creator)) },
        providerHealth = emptyMap(),
        scenarioPreset = ScenarioPreset.NORMAL,
        lastSelectedTabRoute = "search",
        viewer = settings.viewer.copy(automaticTextTranslationEnabled = false, enabledOcrLanguages = emptySet()),
    )
}

private fun validate(backup: ProfileBackup) {
    val library = backup.library
    val profiles = backup.settings.recommendationProfiles.map { it.profileId }
    val codexIds = library.codices.map { it.codexId }
    val postIds = library.posts.map { it.id }
    require(backup.createdAtEpochMs >= 0 && profiles.isNotEmpty() && profiles.size <= 1_000)
    require(profiles.distinct().size == profiles.size && codexIds.distinct().size == codexIds.size)
    require(postIds.distinct().size == postIds.size) { "Backup contains duplicate posts" }
    val knownPosts = postIds.toSet()
    val knownCodices = codexIds.toSet()
    library.codices.forEach { codex ->
        require(codex.name.isNotBlank() && codex.createdAtEpochMs >= 0)
        require(profiles.count { ProfileLibraryIds.belongsTo(codex.codexId, it) } == 1) { "Collection has no unique profile" }
        require(codex.automaticTags == CodexLikesPolicy.normalizeAutomaticTags(codex.automaticTags)) {
            "Backup contains invalid automatic tag groups"
        }
    }
    require(library.items.distinctBy { it.codexId to it.postId }.size == library.items.size)
    require(library.likes.distinctBy { it.profileId to it.postId }.size == library.likes.size)
    require(library.watched.distinctBy { it.post.id to it.section }.size == library.watched.size)
    require(library.searches.distinctBy { it.queryHash }.size == library.searches.size)
    require(library.items.all { it.codexId in knownCodices && it.postId in knownPosts && it.savedAtEpochMs >= 0 })
    require(library.likes.all { it.profileId in profiles && it.postId in knownPosts && it.likedAtEpochMs >= 0 })
    require(library.watched.all { it.post.id in knownPosts && it.maxViewedMediaNumber > 0 && it.viewedAtEpochMs >= 0 })
    require(backup.savedSearches.size <= MAX_SAVED_SEARCHES)
    require(backup.savedSearches.distinctBy { it.id }.size == backup.savedSearches.size)
    require(backup.savedSearches.all { it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= MAX_SAVED_SEARCH_NAME_LENGTH })
    require(backup.readingPositions.size <= MAX_READING_POSITIONS)
    require(backup.readingPositions.distinctBy { it.postId to it.section }.size == backup.readingPositions.size)
    require(backup.customSources.size <= MAX_BACKUP_SITE_PROFILES)
    require(backup.customSources.distinctBy(SiteProfile::sourceId).size == backup.customSources.size) {
        "Backup contains duplicate sites"
    }
}
