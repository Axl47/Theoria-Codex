package com.theoriacodex.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.creator.CreatorProfileCoordinator
import com.theoriacodex.app.recommend.ForYouCoordinator
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.source.exposedRealSources
import com.theoriacodex.app.sourceauth.AndroidSecureSourceCredentialsStore
import com.theoriacodex.app.sourceauth.PixivPkceController
import com.theoriacodex.app.update.AndroidApkInstaller
import com.theoriacodex.app.update.ApkDownloadManager
import com.theoriacodex.app.update.ApkUpdateValidator
import com.theoriacodex.app.update.FileBackedUpdateStateStore
import com.theoriacodex.app.update.GitHubReleaseFeedClient
import com.theoriacodex.app.update.StartupUpdater
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.FileBackedCacheRepository
import com.theoriacodex.data.repository.FileBackedCodexRepository
import com.theoriacodex.data.repository.FileBackedLikesRepository
import com.theoriacodex.data.repository.FileBackedQueryRepository
import com.theoriacodex.data.repository.FileBackedRecentsRepository
import com.theoriacodex.data.repository.FileBackedSettingsRepository
import com.theoriacodex.data.repository.FileBackedUiRestoreRepository
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.RealAdapterRegistry
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import com.theoriacodex.sources.pixiv.PixivAuthApi
import java.io.File

internal data class TheoriaAppGraph(
    val storageDirectory: File,
    val sourceHttpClient: DefaultSourceHttpClient,
    val credentialsStore: AndroidSecureSourceCredentialsStore,
    val pixivAuthApi: PixivAuthApi,
    val pixivAuthController: PixivPkceController,
    val pixivUgoiraClient: PixivUgoiraClient,
    val availableRealSources: Set<SourceKey>,
    val realRegistry: RealAdapterRegistry,
    val updateStateStore: FileBackedUpdateStateStore,
    val updateFeedClient: GitHubReleaseFeedClient,
    val startupUpdater: StartupUpdater,
    val codexRepository: FileBackedCodexRepository,
    val likesRepository: FileBackedLikesRepository,
    val queryRepository: FileBackedQueryRepository,
    val recentsRepository: FileBackedRecentsRepository,
    val settingsRepository: FileBackedSettingsRepository,
    val cacheRepository: FileBackedCacheRepository,
    val uiRestoreRepository: FileBackedUiRestoreRepository,
    val searchCoordinator: SearchCoordinator,
    val forYouCoordinator: ForYouCoordinator,
    val creatorProfileCoordinator: CreatorProfileCoordinator,
)

@Composable
internal fun rememberTheoriaAppGraph(
    appContext: Context,
    rule34XxxConfigured: Boolean,
): TheoriaAppGraph {
    val storageDirectory = remember(appContext) { File(appContext.filesDir, "theoria_codex") }
    val seedTagSuggestions = remember(appContext) {
        loadSeedTagSuggestions(appContext)
    }
    val tagSuggestionStore = remember(storageDirectory, seedTagSuggestions) {
        FileBackedTagSuggestionStore(
            storeFile = File(storageDirectory, "tag_suggestions.json"),
            seedData = seedTagSuggestions,
        )
    }
    val sourceHttpClient = remember { DefaultSourceHttpClient() }
    val pixivAuthApi = remember(sourceHttpClient) { PixivAuthApi(sourceHttpClient) }
    val credentialsStore = remember(appContext) { AndroidSecureSourceCredentialsStore(appContext) }
    val pixivAuthController = remember(credentialsStore, pixivAuthApi) {
        PixivPkceController(
            authApi = pixivAuthApi,
            credentialsProvider = credentialsStore,
        )
    }
    val pixivUgoiraClient = remember(credentialsStore, sourceHttpClient) {
        PixivUgoiraClient(
            credentialsProvider = credentialsStore,
            httpClient = sourceHttpClient,
        )
    }
    val availableRealSources = remember(rule34XxxConfigured) {
        exposedRealSources(rule34XxxConfigured)
    }
    val realRegistry = remember(credentialsStore, sourceHttpClient, availableRealSources) {
        RealAdapterRegistry(
            credentialsProvider = credentialsStore,
            httpClient = sourceHttpClient,
            exposedSources = availableRealSources,
        )
    }
    val updateStateStore = remember(storageDirectory) {
        FileBackedUpdateStateStore(
            file = File(storageDirectory, "update_state.json"),
        )
    }
    val updateFeedClient = remember {
        GitHubReleaseFeedClient(
            owner = BuildConfig.UPDATE_REPO_OWNER,
            repo = BuildConfig.UPDATE_REPO_NAME,
            channel = BuildConfig.UPDATE_CHANNEL,
            assetName = BuildConfig.UPDATE_ASSET_NAME,
        )
    }
    val apkDownloadManager = remember(appContext) {
        ApkDownloadManager(
            context = appContext,
            outputFileName = BuildConfig.UPDATE_ASSET_NAME,
        )
    }
    val apkUpdateValidator = remember(appContext) { ApkUpdateValidator(appContext) }
    val apkInstaller = remember(appContext) { AndroidApkInstaller(appContext) }
    val startupUpdater = remember(
        appContext,
        updateFeedClient,
        apkDownloadManager,
        apkUpdateValidator,
        apkInstaller,
        updateStateStore,
    ) {
        StartupUpdater(
            context = appContext,
            feedClient = updateFeedClient,
            downloadManager = apkDownloadManager,
            validator = apkUpdateValidator,
            installer = apkInstaller,
            stateStore = updateStateStore,
            updateCheckTimeoutMs = BuildConfig.UPDATE_CHECK_TIMEOUT_MS,
        )
    }

    val codexRepository = remember(storageDirectory) { FileBackedCodexRepository(storageDirectory) }
    val likesRepository = remember(storageDirectory) { FileBackedLikesRepository(storageDirectory) }
    val queryRepository = remember(storageDirectory) { FileBackedQueryRepository(storageDirectory) }
    val recentsRepository = remember(storageDirectory) { FileBackedRecentsRepository(storageDirectory) }
    val settingsRepository = remember(storageDirectory) { FileBackedSettingsRepository(storageDirectory) }
    val cacheRepository = remember(storageDirectory) { FileBackedCacheRepository(storageDirectory) }
    val uiRestoreRepository = remember(storageDirectory) { FileBackedUiRestoreRepository(storageDirectory) }
    val searchCoordinator = remember(
        realRegistry,
        queryRepository,
        settingsRepository,
        uiRestoreRepository,
        recentsRepository,
        tagSuggestionStore,
    ) {
        SearchCoordinator(
            registry = realRegistry,
            queryRepository = queryRepository,
            settingsRepository = settingsRepository,
            uiRestoreRepository = uiRestoreRepository,
            recentsRepository = recentsRepository,
            tagSuggestionStore = tagSuggestionStore,
        )
    }
    val forYouCoordinator = remember(realRegistry, settingsRepository, likesRepository, tagSuggestionStore) {
        ForYouCoordinator(
            registry = realRegistry,
            settingsRepository = settingsRepository,
            likesRepository = likesRepository,
            tagSuggestionStore = tagSuggestionStore,
        )
    }
    val creatorProfileCoordinator = remember(realRegistry) {
        CreatorProfileCoordinator(registry = realRegistry)
    }

    return TheoriaAppGraph(
        storageDirectory = storageDirectory,
        sourceHttpClient = sourceHttpClient,
        credentialsStore = credentialsStore,
        pixivAuthApi = pixivAuthApi,
        pixivAuthController = pixivAuthController,
        pixivUgoiraClient = pixivUgoiraClient,
        availableRealSources = availableRealSources,
        realRegistry = realRegistry,
        updateStateStore = updateStateStore,
        updateFeedClient = updateFeedClient,
        startupUpdater = startupUpdater,
        codexRepository = codexRepository,
        likesRepository = likesRepository,
        queryRepository = queryRepository,
        recentsRepository = recentsRepository,
        settingsRepository = settingsRepository,
        cacheRepository = cacheRepository,
        uiRestoreRepository = uiRestoreRepository,
        searchCoordinator = searchCoordinator,
        forYouCoordinator = forYouCoordinator,
        creatorProfileCoordinator = creatorProfileCoordinator,
    )
}

private fun loadSeedTagSuggestions(context: Context): Map<SourceKey, List<TagSuggestion>> {
    val body = runCatching {
        context.assets.open("tag_store.json").bufferedReader().use { it.readText() }
    }.getOrNull() ?: return emptyMap()
    val root = runCatching { Gson().fromJson(body, JsonObject::class.java) }.getOrNull()
        ?: return emptyMap()
    val sources = root.getAsJsonObject("sources") ?: return emptyMap()
    return sources.entrySet().mapNotNull outer@{ (sourceName, value) ->
        val source = runCatching { SourceKey.valueOf(sourceName) }.getOrNull() ?: return@outer null
        val tags = value.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull inner@{ element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@inner null
                val text = obj.get("text")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.trim()
                    .orEmpty()
                if (text.isBlank()) return@inner null
                val postCount = obj.get("postCount")?.takeIf { it.isJsonPrimitive }?.asLong
                TagSuggestion(
                    text = text,
                    type = obj.get("type")
                        ?.takeUnless { it.isJsonNull }
                        ?.asString,
                    count = obj.get("count")
                        ?.takeUnless { it.isJsonNull }
                        ?.asInt ?: postCount?.toInt(),
                )
            }
            .orEmpty()
        if (tags.isEmpty()) null else source to tags
    }.toMap()
}
