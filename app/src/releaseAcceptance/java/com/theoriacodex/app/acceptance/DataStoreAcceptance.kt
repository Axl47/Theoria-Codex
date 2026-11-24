package com.theoriacodex.app.acceptance

import com.theoriacodex.data.storage.withDataStoreScope
import com.theoriacodex.data.repository.CodexPostOpen
import com.theoriacodex.data.repository.DATASTORE_SETTINGS_FILE_NAME
import com.theoriacodex.data.repository.DATASTORE_STATISTICS_FILE_NAME
import com.theoriacodex.data.repository.DATASTORE_UI_RESTORE_FILE_NAME
import com.theoriacodex.data.repository.DataStoreSettingsRepository
import com.theoriacodex.data.repository.DataStoreSiteProfileRepository
import com.theoriacodex.data.repository.DataStoreStatisticsRepository
import com.theoriacodex.data.repository.DataStoreUiRestoreRepository
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.ProviderHealthSnapshotStatus
import com.theoriacodex.data.repository.SITE_PROFILES_FILE_NAME
import com.theoriacodex.data.repository.SearchAnimationFilterRestoreState
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The four DataStore files under R8: values this build writes survive a process restart with readable keys, and
 * files a Gson build wrote still load. [current] is written through the repositories; [shipped] holds literal files.
 */
internal suspend fun writeDataStores(current: File, shipped: File) {
    check(current.mkdirs() && shipped.mkdirs()) { "could not create ${current.path} / ${shipped.path}" }
    withDataStoreScope { scope ->
        val settings = DataStoreSettingsRepository(current, scope)
        settings.updateSettings { it.copy(oldSearchBar = true) }
        check(settings.addFavoriteTag(PROFILE, SourceKey.GELBOORU, listOf("cat_ears", "fox_ears"), FAVORITE_NAME)) { "favorite tag was not added" }
        DataStoreUiRestoreRepository(current, scope).apply {
            setLastTab("codex")
            setSearchScrollState("h", SearchScrollState(7, 12))
            setFeedFabRestoreState("ctx", feedFilters)
            setViewerLaunchContext(launchContext)
        }
        DataStoreStatisticsRepository(current, scope).apply {
            recordAppOpen()
            recordWatchedPost(SourceKey.PIXIV, setOf("cat"), CodexPostOpen("codex", "PIXIV:1", "Label & <x>"))
        }
    }
    shipped.resolve(DATASTORE_SETTINGS_FILE_NAME).writeText(SHIPPED_SETTINGS)
    shipped.resolve(DATASTORE_UI_RESTORE_FILE_NAME).writeText(SHIPPED_UI_RESTORE)
    shipped.resolve(DATASTORE_STATISTICS_FILE_NAME).writeText(SHIPPED_STATISTICS)
    shipped.resolve(SITE_PROFILES_FILE_NAME).writeText(SHIPPED_SITES)
}

internal suspend fun verifyDataStores(current: File, shipped: File) {
    withDataStoreScope { scope ->
        val settings = DataStoreSettingsRepository(current, scope).apply { awaitReady() }.observeSettings().first()
        val favorites = settings.favoriteTagsByProfile[PROFILE]
        check(settings.oldSearchBar && favorites?.singleOrNull()?.name == FAVORITE_NAME) { "oldSearchBar=${settings.oldSearchBar} favorites=$favorites" }
        DataStoreUiRestoreRepository(current, scope).apply {
            awaitReady()
            val lastTab = getLastTab()
            check(lastTab == "codex") { "last tab was $lastTab" }
            val scroll = getSearchScrollState("h")
            check(scroll == SearchScrollState(7, 12)) { "search scroll state was $scroll" }
            val fabStates = getFeedFabRestoreStates()
            check(fabStates == mapOf("ctx" to feedFilters)) { "fab restore states were $fabStates" }
            val launch = observeViewerLaunchContext().first()
            check(launch == launchContext) { "viewer launch context was $launch" }
        }
        val statistics = DataStoreStatisticsRepository(current, scope).apply { awaitReady() }.observeStatistics().first()
        check(statistics.appOpenCount == 1L && statistics.watchedPostCount == 1L) { "statistics open/watched counts were ${statistics.appOpenCount} / ${statistics.watchedPostCount}" }
        val codexPost = statistics.codexStatistics["codex"]?.posts?.get("PIXIV:1")
        check(codexPost?.label == "Label & <x>") { "codex stats PIXIV:1 was $codexPost in ${statistics.codexStatistics}" }
    }
    verifyCurrentKeys(current)
    withDataStoreScope { scope -> verifyShipped(shipped, scope) }
}

private fun verifyCurrentKeys(current: File) {
    val settings = current.json(DATASTORE_SETTINGS_FILE_NAME)
    check(settings.keys == setOf("schemaVersion", "settings")) { "settings keys were ${settings.keys}" }
    val favorite = settings.getValue("settings").jsonObject.getValue("favoriteTagsByProfile").jsonObject
        .getValue(PROFILE).jsonArray.single().jsonObject
    check(favorite.keys == setOf("source", "tag", "tags", "name")) { "favorite keys were ${favorite.keys}" }
    val restore = current.json(DATASTORE_UI_RESTORE_FILE_NAME)
    check(restore.keys == setOf("schemaVersion", "state")) { "restore keys were ${restore.keys}" }
    val state = restore.getValue("state").jsonObject
    check(state.keys ==
        setOf("lastTab", "searchScrollStates", "feedFabRestoreStates", "viewerLaunchContext", "savedSearchNewPosts")) { "state keys were ${state.keys}" }
    check(state.getValue("searchScrollStates").jsonObject.getValue("h").jsonObject.keys ==
        setOf("firstVisibleItemIndex", "firstVisibleItemOffsetPx")) { "scroll state keys were ${state.getValue("searchScrollStates").jsonObject.getValue("h").jsonObject.keys}" }
    check(state.getValue("viewerLaunchContext").jsonObject.keys ==
        setOf("queryHash", "startIndex", "streamSource", "scrollOffsetHint")) { "launch context keys were ${state.getValue("viewerLaunchContext").jsonObject.keys}" }
    val statistics = current.json(DATASTORE_STATISTICS_FILE_NAME)
    check(statistics.keys == setOf("schemaVersion", "statistics")) { "statistics keys were ${statistics.keys}" }
    val post = statistics.getValue("statistics").jsonObject.getValue("codexStatistics").jsonObject.getValue("codex").jsonObject
        .getValue("posts").jsonObject.getValue("PIXIV:1").jsonObject
    check(post.keys == setOf("label", "opens", "videoMs", "imageMs")) { "post stats keys were ${post.keys}" }
}

private suspend fun verifyShipped(shipped: File, scope: CoroutineScope) {
    val settings = DataStoreSettingsRepository(shipped, scope).apply { awaitReady() }.observeSettings().first()
    check(settings.oldSearchBar && settings.followedCreators.single().membershipId == "follow-1") { "oldSearchBar=${settings.oldSearchBar} followed creators=${settings.followedCreators}" }
    check(settings.providerHealth.getValue(SourceKey.PIXIV).status == ProviderHealthSnapshotStatus.DEGRADED) { "pixiv provider health was ${settings.providerHealth[SourceKey.PIXIV]}" }
    DataStoreUiRestoreRepository(shipped, scope).apply {
        awaitReady()
        val lastTab = getLastTab()
        check(lastTab == "codex") { "last tab was $lastTab" }
        val scroll = getSearchScrollState("h")
        check(scroll == SearchScrollState(7, 12)) { "search scroll state was $scroll" }
        val fabStates = getFeedFabRestoreStates()
        check(fabStates.getValue("ctx") == feedFilters) { "fab restore states were $fabStates" }
        val launch = observeViewerLaunchContext().first()
        check(launch == launchContext) { "viewer launch context was $launch" }
    }
    val statistics = DataStoreStatisticsRepository(shipped, scope).apply { awaitReady() }.observeStatistics().first()
    check(statistics.appOpenCount == 3L && statistics.history.getValue(LocalDate.of(2026, 11, 2)).watchedPostCount == 2L) { "open count ${statistics.appOpenCount} history ${statistics.history}" }
    check(statistics.codexStatistics.getValue("c").posts.getValue("PIXIV:1").opens == 2L) { "codex stats c was ${statistics.codexStatistics["c"]}" }
    val sites = DataStoreSiteProfileRepository(shipped, scope).apply { awaitReady() }
    val profiles = sites.observeProfiles().first()
    check(profiles.single().label == "Safe") { "site profiles were $profiles" }
    check(sites.update(SITE_ID) { it.copy(label = "Renamed") }) { "site update did not apply" }
    val site = shipped.json(SITE_PROFILES_FILE_NAME).getValue("sites").jsonArray.single().jsonObject
    check(site.getValue("label").jsonPrimitive.content == "Renamed") { "site label json was ${site["label"]}" }
    check(site.keys == setOf("sourceId", "baseUrl", "label", "family", "credentialsRequired", "addedAt")) { "site keys were ${site.keys}" }
}

private fun File.json(name: String): JsonObject = Json.parseToJsonElement(resolve(name).readText()).jsonObject

private const val PROFILE = "profile-main"
private const val FAVORITE_NAME = "Ears & <co>"
private const val SITE_ID = "custom_safebooru_org"

private val feedFilters = FeedFabRestoreState(
    followedSources = listOf("PIXIV"),
    animatedOnly = true,
    sortMode = "NEWEST",
    searchAnimationByScope = mapOf("s" to SearchAnimationFilterRestoreState(1, 9)),
)

private val launchContext = ViewerLaunchContext("q", 4, ViewerStreamSource.CODEX, 21)

// What Gson builds wrote: Gson's HTML escapes and since-retired keys included.
private const val SHIPPED_SETTINGS = """{"schemaVersion":3,"settings":{"followedCreators":[{"source":"PIXIV",""" +
    """"name":"Artist","profileId":"3","uploadsQuery":"user:3","membershipId":"follow-1","seenPostIds":[],""" +
    """"latestPostIds":[]}],"sourceCatalogVersion":3,"enabledSources":["PIXIV"],"sourceWeights":{"PIXIV":1.0},""" +
    """"oldSearchBar":true,"lastSelectedTabRoute":"codex","recommendationProfiles":[{"profileId":"profile-main",""" +
    """"name":"Main & more"}],"activeProfileId":"profile-main","providerHealth":[{"source":"PIXIV",""" +
    """"status":"DEGRADED","checkedAtEpochMs":5}]},"legacyImports":[]}"""

private const val SHIPPED_UI_RESTORE = """{"schemaVersion":2,"state":{"lastTab":"codex","searchScrollStates":""" +
    """{"h":{"firstVisibleItemIndex":7,"firstVisibleItemOffsetPx":12}},"feedFabRestoreStates":{"ctx":{""" +
    """"excludedSavedSearches":[],"followedSources":["PIXIV"],"followedAuthors":[],"animatedOnly":true,""" +
    """"hideLiked":false,"hideSaved":false,"hideWatched":false,"durationMinBucket":0,"durationMaxBucket":25,""" +
    """"sortMode":"NEWEST","fullColorOnly":false,"searchAnimationByScope":{"s":{"animatedOnly":true,""" +
    """"durationMinBucket":1,"durationMaxBucket":9}}}},"viewerLaunchContext":{"queryHash":"q","startIndex":4,""" +
    """"streamSource":"CODEX","scrollOffsetHint":21}},"legacyImports":[]}"""

private const val SHIPPED_STATISTICS = """{"schemaVersion":2,"statistics":{"history":{"2026-11-02":""" +
    """{"appOpenCount":1,"watchedPostCount":2}},"codexStatistics":{"c":{"browsingMs":5,"posts":{"PIXIV:1":""" +
    """{"label":"L","opens":2}}}},"appOpenCount":3,"watchedBySource":{"PIXIV":2},"watchedByTag":""" +
    """[{"source":"PIXIV","tag":"cat","count":2}],"searchesByTypedTag":[{"source":"PIXIV","tag":"a",""" +
    """"facet":"ARTIST","sourceNamespace":"artist","count":1}]}}"""

private const val SHIPPED_SITES = """{"schemaVersion":1,"sites":[{"sourceId":"custom_safebooru_org",""" +
    """"baseUrl":"https://safebooru.org","label":"Safe","family":"GELBOORU_02","credentialsRequired":false,""" +
    """"addedAt":1}]}"""
