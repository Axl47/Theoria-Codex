package com.theoriacodex.app.di

import android.content.Context
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.creator.CreatorProfileCoordinator
import com.theoriacodex.app.media.AnimatedDurationEnricher
import com.theoriacodex.app.media.AnimatedDurationEnrichmentService
import com.theoriacodex.app.codex.LikesCodexSyncService
import com.theoriacodex.app.codex.transfer.CodexTransferService
import com.theoriacodex.app.recommend.ForYouCoordinator
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.search.loadSeedTagSuggestions
import com.theoriacodex.app.source.exposedRealSources
import com.theoriacodex.app.sourceauth.AndroidSecureSourceCredentialsStore
import com.theoriacodex.app.sourceauth.AndroidPixivPkceSessionStore
import com.theoriacodex.app.sourceauth.ObservableSourceAccountStore
import com.theoriacodex.app.sourceauth.PixivPkceController
import com.theoriacodex.app.sourceauth.SourceAccountStore
import com.theoriacodex.app.update.AndroidApkInstaller
import com.theoriacodex.app.update.ApkDownloadManager
import com.theoriacodex.app.update.ApkUpdateValidator
import com.theoriacodex.app.update.FileBackedUpdateStateStore
import com.theoriacodex.app.update.GitHubReleaseFeedClient
import com.theoriacodex.app.update.StartupUpdater
import com.theoriacodex.app.update.UpdateFeedClient
import com.theoriacodex.app.update.UpdateStateStore
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexLikesTransactions
import com.theoriacodex.data.repository.FileBackedCacheRepository
import com.theoriacodex.data.repository.FileBackedQueryRepository
import com.theoriacodex.data.repository.FileBackedRecentsRepository
import com.theoriacodex.data.repository.DataStoreSettingsRepository
import com.theoriacodex.data.repository.DataStoreUiRestoreRepository
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.QueryRepository
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.data.android.room.LegacyArchiveResult
import com.theoriacodex.data.android.room.LegacyJsonImportResult
import com.theoriacodex.data.android.room.LegacyJsonMigrationException
import com.theoriacodex.data.android.room.RoomCodexLikesRepository
import com.theoriacodex.data.android.room.RoomLegacyJsonImporter
import com.theoriacodex.data.android.room.TheoriaRoomDatabase
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.RealAdapterRegistry
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.pixiv.PixivAuthApi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow

data class DataDependencies(
    val storageDirectory: File,
    val codexRepository: CodexRepository,
    val likesRepository: LikesRepository,
    val queryRepository: QueryRepository,
    val recentsRepository: RecentsRepository,
    val settingsRepository: SettingsRepository,
    val cacheRepository: CacheRepository,
    val uiRestoreRepository: UiRestoreRepository,
)

data class SourceDependencies(
    val httpClient: SourceHttpClient,
    val accounts: SourceAccountStore,
    val pixivAuthApi: PixivAuthApi,
    val pixivAuthController: PixivPkceController,
    val pixivUgoiraClient: PixivUgoiraClient,
    val registry: SourceAdapterRegistry,
    val availableSources: StateFlow<Set<SourceKey>>,
)

data class UpdateDependencies(
    val stateStore: UpdateStateStore,
    val feedClient: UpdateFeedClient,
    val startupUpdater: StartupUpdater,
)

data class FeatureDependencies(
    val search: SearchCoordinator,
    val forYou: ForYouCoordinator,
    val creatorProfile: CreatorProfileCoordinator,
    val animatedDurationEnricher: AnimatedDurationEnricher,
)

data class WorkflowDependencies(
    val likesCodexSync: LikesCodexSyncService,
    val codexTransfer: CodexTransferService,
)

interface TheoriaAppContainer {
    val data: DataDependencies
    val sources: SourceDependencies
    val updates: UpdateDependencies
    val features: FeatureDependencies
    val workflows: WorkflowDependencies
}

interface TheoriaAppContainerOwner {
    val appContainerState: StateFlow<com.theoriacodex.data.storage.ApplicationDataState<TheoriaAppContainer>>

    fun startAppContainer()

    suspend fun awaitAppContainer(): TheoriaAppContainer

    suspend fun retryAppContainer(): TheoriaAppContainer
}

internal class DefaultTheoriaAppContainer(
    context: Context,
) : TheoriaAppContainer {
    private val appContext = context.applicationContext
    private val storageDirectory = File(appContext.filesDir, "theoria_codex")
    private val durableStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tagSuggestionStore = FileBackedTagSuggestionStore(
        storeFile = File(storageDirectory, "tag_suggestions.json"),
        seedData = loadSeedTagSuggestions(appContext),
    )
    private val sourceHttpClient = DefaultSourceHttpClient()
    private val accountStore = ObservableSourceAccountStore(
        delegate = AndroidSecureSourceCredentialsStore(appContext),
    )
    private val pixivAuthApi = PixivAuthApi(sourceHttpClient)
    private val pixivAuthController = PixivPkceController(
        authApi = pixivAuthApi,
        credentialsProvider = accountStore,
        sessionStore = AndroidPixivPkceSessionStore(appContext),
    )
    private val pixivUgoiraClient = PixivUgoiraClient(
        credentialsProvider = accountStore,
        httpClient = sourceHttpClient,
    )
    private val allPotentialSourceRegistry = RealAdapterRegistry(
        credentialsProvider = accountStore,
        httpClient = sourceHttpClient,
        exposedSources = exposedRealSources(rule34XxxConfigured = true),
    )
    private val sourceRegistry = AvailabilityAwareSourceAdapterRegistry(
        delegate = allPotentialSourceRegistry,
        availableSourceState = accountStore.availableSources,
    )
    private val animatedDurationEnricher = AnimatedDurationEnrichmentService(
        registry = sourceRegistry,
    )

    private val contentDatabase = TheoriaRoomDatabase.create(appContext)
    private val contentRepository = RoomCodexLikesRepository(contentDatabase)
    private val contentImporter = RoomLegacyJsonImporter(contentDatabase)
    private val codexRepository: CodexRepository = contentRepository
    private val likesRepository: LikesRepository = contentRepository
    private val codexLikesTransactions: CodexLikesTransactions = contentRepository
    private val queryRepository = FileBackedQueryRepository(storageDirectory)
    private val recentsRepository = FileBackedRecentsRepository(storageDirectory)
    private val settingsRepository = DataStoreSettingsRepository(
        baseDirectory = storageDirectory,
        scope = durableStoreScope,
    )
    private val cacheRepository = FileBackedCacheRepository(storageDirectory)
    private val uiRestoreRepository = DataStoreUiRestoreRepository(
        baseDirectory = storageDirectory,
        scope = durableStoreScope,
    )

    private val updateStateStore = FileBackedUpdateStateStore(
        file = File(storageDirectory, "update_state.json"),
    )
    private val updateFeedClient = GitHubReleaseFeedClient(
        owner = BuildConfig.UPDATE_REPO_OWNER,
        repo = BuildConfig.UPDATE_REPO_NAME,
        channel = BuildConfig.UPDATE_CHANNEL,
        assetName = BuildConfig.UPDATE_ASSET_NAME,
    )
    private val startupUpdater = StartupUpdater(
        context = appContext,
        feedClient = updateFeedClient,
        downloadManager = ApkDownloadManager(
            context = appContext,
            outputFileName = BuildConfig.UPDATE_ASSET_NAME,
        ),
        validator = ApkUpdateValidator(appContext),
        installer = AndroidApkInstaller(appContext),
        stateStore = updateStateStore,
        updateCheckTimeoutMs = BuildConfig.UPDATE_CHECK_TIMEOUT_MS,
    )

    override val data = DataDependencies(
        storageDirectory = storageDirectory,
        codexRepository = codexRepository,
        likesRepository = likesRepository,
        queryRepository = queryRepository,
        recentsRepository = recentsRepository,
        settingsRepository = settingsRepository,
        cacheRepository = cacheRepository,
        uiRestoreRepository = uiRestoreRepository,
    )

    override val sources = SourceDependencies(
        httpClient = sourceHttpClient,
        accounts = accountStore,
        pixivAuthApi = pixivAuthApi,
        pixivAuthController = pixivAuthController,
        pixivUgoiraClient = pixivUgoiraClient,
        registry = sourceRegistry,
        availableSources = accountStore.availableSources,
    )

    override val updates = UpdateDependencies(
        stateStore = updateStateStore,
        feedClient = updateFeedClient,
        startupUpdater = startupUpdater,
    )

    override val features = FeatureDependencies(
        search = SearchCoordinator(
            registry = sourceRegistry,
            queryRepository = queryRepository,
            settingsRepository = settingsRepository,
            uiRestoreRepository = uiRestoreRepository,
            recentsRepository = recentsRepository,
            tagSuggestionStore = tagSuggestionStore,
        ),
        forYou = ForYouCoordinator(
            registry = sourceRegistry,
            settingsRepository = settingsRepository,
            likesRepository = likesRepository,
            tagSuggestionStore = tagSuggestionStore,
        ),
        creatorProfile = CreatorProfileCoordinator(registry = sourceRegistry),
        animatedDurationEnricher = animatedDurationEnricher,
    )

    override val workflows = WorkflowDependencies(
        likesCodexSync = LikesCodexSyncService(
            transactions = codexLikesTransactions,
            codexRepository = codexRepository,
        ),
        codexTransfer = CodexTransferService(
            codexRepository = codexRepository,
            transactions = codexLikesTransactions,
            cacheRepository = cacheRepository,
            sourceRegistry = sourceRegistry,
        ),
    )

    /** Complete typed-store migration before any route can observe default placeholder state. */
    suspend fun awaitDurableStores() = coroutineScope {
        val settingsReady = async { settingsRepository.awaitReady() }
        val uiRestoreReady = async { uiRestoreRepository.awaitReady() }
        val contentReady = async { awaitContentStore() }
        settingsReady.await()
        uiRestoreReady.await()
        contentReady.await()
    }

    private suspend fun awaitContentStore() {
        when (val imported = contentImporter.importIfNeeded(storageDirectory)) {
            is LegacyJsonImportResult.Imported,
            is LegacyJsonImportResult.AdoptedVerifiedDestination,
            is LegacyJsonImportResult.AlreadyImported -> Unit

            is LegacyJsonImportResult.SplitBrain -> throw LegacyJsonMigrationException(
                "Legacy Codex/Likes data changed after its verified Room migration",
            )
            is LegacyJsonImportResult.DestinationConflict -> throw LegacyJsonMigrationException(
                "Room Codex/Likes data conflicts with the retained legacy snapshot",
            )
            is LegacyJsonImportResult.DestinationDrift -> throw LegacyJsonMigrationException(
                "Room Codex/Likes data no longer matches its verified migration proof",
            )
            is LegacyJsonImportResult.InvalidStoredProof -> throw LegacyJsonMigrationException(
                "Room Codex/Likes migration proof is invalid: ${imported.reason}",
            )
        }
        when (val archived = contentImporter.archiveImportedSources(storageDirectory)) {
            is LegacyArchiveResult.Complete -> Unit
            LegacyArchiveResult.NotImported -> throw LegacyJsonMigrationException(
                "Room Codex/Likes migration completed without a durable proof",
            )
            is LegacyArchiveResult.InvalidStoredProof -> throw LegacyJsonMigrationException(
                "Room Codex/Likes archive proof is invalid: ${archived.reason}",
            )
            is LegacyArchiveResult.Partial -> throw LegacyJsonMigrationException(
                "Room Codex/Likes legacy archive is incomplete for ${archived.blockedFile}: " +
                    archived.reason,
            )
        }
    }
}
