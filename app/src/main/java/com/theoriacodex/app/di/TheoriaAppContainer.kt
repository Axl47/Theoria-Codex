package com.theoriacodex.app.di

import com.theoriacodex.app.media.NetworkMonitor
import com.theoriacodex.app.network.AppHttpClients
import android.content.Context
import androidx.core.net.toUri
import com.theoriacodex.app.backup.AndroidAutoBackupFolder
import com.theoriacodex.app.backup.AutoBackupWorkflow
import com.theoriacodex.app.backup.SharedPreferencesAutoBackupStore
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.creator.CreatorProfileCoordinator
import com.theoriacodex.app.customsites.CustomSitesController
import com.theoriacodex.app.customsites.SharedPreferencesBuiltInAgentStore
import com.theoriacodex.app.customsites.SiteCookieBridge
import com.theoriacodex.app.media.BoundedMediaDurationProbe
import com.theoriacodex.app.media.MediaDurationAcquisitionEngine
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.media.OfflineAwareCacheRepository
import com.theoriacodex.app.media.OfflineMediaAcquisition
import com.theoriacodex.app.media.OfflineMediaCoordinator
import com.theoriacodex.app.codex.LikesCodexSyncService
import com.theoriacodex.app.codex.transfer.CodexTransferService
import com.theoriacodex.app.recommend.ForYouCoordinator
import com.theoriacodex.app.comments.CommentsLoader
import com.theoriacodex.app.comments.CommentsLoading
import com.theoriacodex.app.related.RelatedPostsLoader
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.search.loadSeedTagSuggestions
import com.theoriacodex.app.statistics.AppUsageTracker
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
import com.theoriacodex.app.viewer.videoPlaybackInfrastructure
import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.SearchFirstPageStore
import com.theoriacodex.app.codex.CodexCleaningService
import com.theoriacodex.data.repository.CodexLikesTransactions
import com.theoriacodex.data.repository.FileBackedCacheRepository
import com.theoriacodex.data.repository.FileBackedQueryRepository
import com.theoriacodex.data.repository.DataStoreSettingsRepository
import com.theoriacodex.data.repository.DataStoreSiteProfileRepository
import com.theoriacodex.data.repository.DataStoreStatisticsRepository
import com.theoriacodex.data.repository.DataStoreUiRestoreRepository
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.MediaDurationRepository
import com.theoriacodex.data.repository.QueryRepository
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.ReadingPositionRepository
import com.theoriacodex.data.repository.FileBackedReadingPositionRepository
import com.theoriacodex.data.repository.InMemoryReadingPositionRepository
import com.theoriacodex.data.repository.FileBackedSavedSearchRepository
import com.theoriacodex.data.repository.InMemorySavedSearchRepository
import com.theoriacodex.data.repository.SavedSearchRepository
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.repository.OfflinePendingJobStore
import com.theoriacodex.data.repository.ProfileBackupService
import com.theoriacodex.data.repository.ProfileDeletionService
import com.theoriacodex.data.repository.ProfileLibraryWriteGuard
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.data.storage.CorruptionRecovery
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.data.android.room.RoomCodexLikesRepository
import com.theoriacodex.data.android.room.RoomMediaDurationRepository
import com.theoriacodex.data.android.room.RoomRecentsRepository
import com.theoriacodex.data.android.room.RoomProfileBackupStore
import com.theoriacodex.data.android.room.awaitProfileLibraryStores
import com.theoriacodex.data.android.room.TheoriaRoomDatabase
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.RealAdapterRegistry
import com.theoriacodex.sources.custom.CustomSiteHttpClient
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.sources.http.SiteHealthCheck
import com.theoriacodex.app.media.isMediaNetworkMetered
import com.theoriacodex.app.source.PostResolutionFlights
import com.theoriacodex.sources.custom.SiteFamilyRegistry
import com.theoriacodex.sources.custom.SiteProber
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.pixiv.PixivAuthApi
import com.theoriacodex.sources.pixiv.PixivTokenCoordinator
import com.theoriacodex.sources.pixiv.PixivUgoiraMetadataCache
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DataDependencies(
    val storageDirectory: File,
    val codexRepository: CodexRepository,
    val likesRepository: LikesRepository,
    val queryRepository: QueryRepository,
    val recentsRepository: RecentsRepository,
    val settingsRepository: SettingsRepository,
    val statisticsRepository: StatisticsRepository,
    val cacheRepository: CacheRepository,
    val uiRestoreRepository: UiRestoreRepository,
    val mediaDurationRepository: MediaDurationRepository,
    val legacyJsonRecoveries: StateFlow<List<CorruptionRecovery>>,
    val readingPositions: ReadingPositionRepository = InMemoryReadingPositionRepository(),
    val savedSearches: SavedSearchRepository = InMemorySavedSearchRepository(),
    /** This session's copies of one file across sites; feeds teach it what they list. */
    val fileMirrors: FileMirrorIndex = FileMirrorIndex(),
)

data class SourceDependencies(
    val httpClient: SourceHttpClient,
    val accounts: SourceAccountStore,
    val pixivAuthApi: PixivAuthApi,
    val pixivAuthController: PixivPkceController,
    val pixivUgoiraClient: PixivUgoiraClient,
    val registry: SourceAdapterRegistry,
    val availableSources: StateFlow<Set<SourceKey>>,
    val networkOnline: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true),
    val pixivTokens: PixivTokenCoordinator = PixivTokenCoordinator(accounts, pixivAuthApi),
    /** Hosts known to be down this session; shared by the built-in and custom-site clients. */
    val outages: HostOutageTracker = HostOutageTracker(),
    /** The light per-site check, sharing the app's client so it carries browser-verified cookies. */
    val healthCheck: SiteHealthCheck = SiteHealthCheck(outages),
    /** Opens connections to recently used hosts, on networks where the traffic costs nothing. */
    val warmConnections: suspend () -> Unit = {},
    /** One in-flight detail request per post, shared by the feed and the Viewer. */
    val postResolutions: PostResolutionFlights = PostResolutionFlights(),
)

data class UpdateDependencies(
    val stateStore: UpdateStateStore,
    val feedClient: UpdateFeedClient,
    val startupUpdater: StartupUpdater,
)

data class FeatureDependencies(
    val search: SearchCoordinator,
    val forYou: ForYouCoordinator,
    val relatedPosts: RelatedPostsLoader,
    val comments: CommentsLoading,
    val creatorProfile: CreatorProfileCoordinator,
    val mediaDurationCoordinator: MediaDurationCoordinator,
    val appUsageTracker: AppUsageTracker,
    val offlineMedia: OfflineMediaCoordinator? = null,
)

data class WorkflowDependencies(
    val codexCleaning: CodexCleaningService,
    val likesCodexSync: LikesCodexSyncService,
    val codexTransfer: CodexTransferService,
    val profileBackup: ProfileBackupService? = null,
    val autoBackup: AutoBackupWorkflow? = null,
    val profileDeletion: ProfileDeletionService? = null,
)

interface TheoriaAppContainer {
    val data: DataDependencies
    val sources: SourceDependencies
    val updates: UpdateDependencies
    val features: FeatureDependencies
    val workflows: WorkflowDependencies

    /** Settings → Sites: user-added booru sites. */
    val customSites: CustomSitesController
}

interface TheoriaAppContainerOwner {
    val appContainerState: StateFlow<com.theoriacodex.data.storage.ApplicationDataState<TheoriaAppContainer>>

    fun startAppContainer()

    suspend fun awaitAppContainer(): TheoriaAppContainer

    suspend fun retryAppContainer(): TheoriaAppContainer
}

internal class DefaultTheoriaAppContainer(
    context: Context,
    private val cookieBridge: SiteCookieBridge,
    private val httpClients: AppHttpClients,
) : TheoriaAppContainer {
    private val appContext = context.applicationContext
    private val storageDirectory = File(appContext.filesDir, "theoria_codex")
    private val durableStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val offlineStore = OfflineMediaStore(File(storageDirectory, "offline_media"))
    private val legacyJsonRecoveryRegistry = LegacyJsonRecoveryRegistry()
    private val tagSuggestionStore = FileBackedTagSuggestionStore(
        storeFile = File(storageDirectory, "tag_suggestions.json"),
        seedData = loadSeedTagSuggestions(appContext),
        recoveryRegistry = legacyJsonRecoveryRegistry,
    )
    private val networkOnline = NetworkMonitor(appContext).online
    private val hostOutages = HostOutageTracker(isOnline = { networkOnline.value })
    private val siteHealthCheck = SiteHealthCheck(hostOutages, httpClients.sources)
    private val builtInAgents = SharedPreferencesBuiltInAgentStore(appContext)
    private val sourceHttpClient = DefaultSourceHttpClient(
        outages = hostOutages,
        userAgentFor = cookieBridge::userAgentFor,
        okHttpClient = httpClients.sources,
    )
    private val credentialStore = AndroidSecureSourceCredentialsStore(appContext)
    private val accountStore = ObservableSourceAccountStore(delegate = credentialStore)
    private val pixivAuthApi = PixivAuthApi(sourceHttpClient)
    private val pixivTokenCoordinator = PixivTokenCoordinator(
        credentialsProvider = accountStore,
        authApi = pixivAuthApi,
    )
    private val pixivAuthController = PixivPkceController(
        authApi = pixivAuthApi,
        credentialsProvider = accountStore,
        sessionStore = AndroidPixivPkceSessionStore(appContext),
    )
    private val pixivUgoiraMetadataCache = PixivUgoiraMetadataCache()
    private val pixivUgoiraClient = PixivUgoiraClient(
        credentialsProvider = accountStore,
        httpClient = sourceHttpClient,
        tokenCoordinator = pixivTokenCoordinator,
        archiveDirectory = File(appContext.cacheDir, "theoria_codex/pixiv/ugoira"),
        offlineArchiveLookup = { id ->
            offlineStore.find(PostId(SourceKey.PIXIV, id))?.full?.localPath?.let(::File)
        },
        metadataCache = pixivUgoiraMetadataCache,
        downloadClient = httpClients.downloads,
    )
    private val allPotentialSourceRegistry = RealAdapterRegistry(
        credentialsProvider = accountStore,
        httpClient = sourceHttpClient,
        exposedSources = exposedRealSources(rule34XxxConfigured = true),
        pixivTokenCoordinator = pixivTokenCoordinator,
        pixivUgoiraMetadataCache = pixivUgoiraMetadataCache,
    )
    private val siteProfiles = DataStoreSiteProfileRepository(baseDirectory = storageDirectory, scope = durableStoreScope)
    private val siteFamilies = SiteFamilyRegistry.default()
    private val customSourceRuntime = CustomSourceRuntime(
        profiles = siteProfiles,
        credentials = credentialStore,
        families = siteFamilies,
        http = CustomSiteHttpClient(
            readTimeoutMs = CUSTOM_SITE_READ_TIMEOUT_MS,
            outages = hostOutages,
            okHttpClient = httpClients.sources,
        ),
        scope = durableStoreScope,
        publishMediaKeys = cookieBridge::updateMediaKeys,
    )
    private val sourceRegistry = CompositeSourceAdapterRegistry(
        builtIn = AvailabilityAwareSourceAdapterRegistry(
            delegate = allPotentialSourceRegistry,
            availableSourceState = accountStore.availableSources,
        ),
        potentialBuiltIns = allPotentialSourceRegistry,
        custom = customSourceRuntime.snapshot,
    )
    /** Built-ins the account store allows plus usable custom sites; Settings intersects saves with it. */
    private val availableSources: StateFlow<Set<SourceKey>> =
        combine(accountStore.availableSources, customSourceRuntime.snapshot) { builtIns, custom -> builtIns + custom.adapters.keys }
            .stateIn(durableStoreScope, SharingStarted.Eagerly, accountStore.availableSources.value)
    private val contentDatabase = TheoriaRoomDatabase.create(appContext)
    private val mediaDurationRepository = RoomMediaDurationRepository(contentDatabase)
    private val boundedMediaDurationProbe = BoundedMediaDurationProbe(sourceHttpClient)
    private val mediaDurationAcquisitionEngine = MediaDurationAcquisitionEngine(
        registry = sourceRegistry,
        probe = boundedMediaDurationProbe,
    )
    private val mediaDurationCoordinator = MediaDurationCoordinator(
        acquirer = mediaDurationAcquisitionEngine,
        durationRepository = mediaDurationRepository,
        parentScope = durableStoreScope,
    )

    private val profileWriteGuard = ProfileLibraryWriteGuard()
    private val contentRepository = RoomCodexLikesRepository(contentDatabase, writeGuard = profileWriteGuard)
    private val codexRepository: CodexRepository = contentRepository
    private val likesRepository: LikesRepository = contentRepository
    private val codexLikesTransactions: CodexLikesTransactions = contentRepository
    private val queryRepository = FileBackedQueryRepository(
        storageDirectory,
        recoveryRegistry = legacyJsonRecoveryRegistry,
    )
    private val recentsRepository = RoomRecentsRepository(contentDatabase)
    private val settingsRepository = DataStoreSettingsRepository(
        baseDirectory = storageDirectory,
        scope = durableStoreScope,
        profileWriteGuard = profileWriteGuard,
    )
    private val profileDeletion = ProfileDeletionService(settingsRepository, contentRepository,
        File(storageDirectory, "pending_profile_deletion.json"), profileWriteGuard)
    private val statisticsStore = DataStoreStatisticsRepository(baseDirectory = storageDirectory, scope = durableStoreScope)
    private val statisticsRepository = com.theoriacodex.app.statistics.ThumbnailStatisticsRepository(
        delegate = statisticsStore,
        scope = durableStoreScope,
        store = com.theoriacodex.data.repository.StatisticsThumbnailStore(File(appContext.filesDir, "statistics_thumbnails")),
        fetch = { post, destination ->
            com.theoriacodex.app.statistics.fetchStatisticsThumbnail(appContext, post, destination)
        },
    )
    private val offlineAcquisition = OfflineMediaAcquisition(appContext, sourceRegistry, settingsRepository, pixivUgoiraClient) {
            url, headers, destination ->
        appContext.videoPlaybackInfrastructure().copyFullyCached(url, headers, destination)
    }
    private val offlineMedia = OfflineMediaCoordinator(
        store = offlineStore,
        scope = durableStoreScope,
        resolve = offlineAcquisition::resolve,
        selectMedia = offlineAcquisition::selectMedia,
        acquire = offlineAcquisition::acquire,
        pendingStore = OfflinePendingJobStore(File(storageDirectory, "offline_jobs.json")),
        ownerExists = { owner -> owner == com.theoriacodex.app.media.AUTOMATIC_OFFLINE_OWNER ||
            owner.startsWith("codex:") && contentRepository.observeCodex(owner.removePrefix("codex:")).first() != null },
    )
    private val cacheRepository = OfflineAwareCacheRepository(FileBackedCacheRepository(storageDirectory), offlineMedia)
    private val readingPositions = FileBackedReadingPositionRepository(storageDirectory)
    private val savedSearches = FileBackedSavedSearchRepository(storageDirectory, recoveryRegistry = legacyJsonRecoveryRegistry)
    private val profileBackup = ProfileBackupService(
        settings = settingsRepository,
        store = RoomProfileBackupStore(contentDatabase),
        savedSearches = savedSearches,
        readingPositions = readingPositions,
        journalFile = File(storageDirectory, "pending_profile_restore.json"),
        statistics = statisticsStore,
        writeGuard = profileWriteGuard,
        siteProfiles = siteProfiles,
    )
    private val autoBackup = AutoBackupWorkflow(
        store = SharedPreferencesAutoBackupStore(appContext),
        openFolder = { AndroidAutoBackupFolder(appContext.contentResolver, it.toUri()) },
        export = { profileBackup.export(includeRecents = true) },
        scope = durableStoreScope,
    )
    private val uiRestoreRepository = DataStoreUiRestoreRepository(
        baseDirectory = storageDirectory,
        scope = durableStoreScope,
    )
    private val appUsageTracker = AppUsageTracker(
        repository = statisticsRepository,
        scope = durableStoreScope,
    )
    private val updateStateStore = FileBackedUpdateStateStore(
        file = File(storageDirectory, "update_state.json"),
        recoveryRegistry = legacyJsonRecoveryRegistry,
    )
    private val updateFeedClient = GitHubReleaseFeedClient(
        owner = BuildConfig.UPDATE_REPO_OWNER,
        repo = BuildConfig.UPDATE_REPO_NAME,
        channel = BuildConfig.UPDATE_CHANNEL,
        assetName = BuildConfig.UPDATE_ASSET_NAME,
        httpClient = sourceHttpClient,
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
        statisticsRepository = statisticsRepository,
        cacheRepository = cacheRepository,
        uiRestoreRepository = uiRestoreRepository,
        mediaDurationRepository = mediaDurationRepository,
        legacyJsonRecoveries = legacyJsonRecoveryRegistry.recoveries,
        readingPositions = readingPositions,
        savedSearches = savedSearches,
    )

    private val postResolutions = PostResolutionFlights()

    override val sources = SourceDependencies(
        httpClient = sourceHttpClient,
        accounts = accountStore,
        pixivAuthApi = pixivAuthApi,
        pixivAuthController = pixivAuthController,
        pixivUgoiraClient = pixivUgoiraClient,
        registry = sourceRegistry,
        availableSources = availableSources,
        networkOnline = networkOnline,
        pixivTokens = pixivTokenCoordinator,
        outages = hostOutages,
        healthCheck = siteHealthCheck,
        warmConnections = { if (!appContext.isMediaNetworkMetered()) httpClients.warmRecentHosts() },
        postResolutions = postResolutions,
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
            statisticsRepository = statisticsRepository,
            tagSuggestionStore = tagSuggestionStore,
            firstPageStore = SearchFirstPageStore(File(appContext.cacheDir, SearchFirstPageStore.FILE_NAME)),
            postResolutions = postResolutions,
            fileMirrors = data.fileMirrors,
        ),
        forYou = ForYouCoordinator(
            registry = sourceRegistry,
            settingsRepository = settingsRepository,
            likesRepository = likesRepository,
            savedSearches = savedSearches,
            recentsRepository = recentsRepository,
            statisticsRepository = statisticsRepository,
            tagSuggestionStore = tagSuggestionStore,
            postResolutions = postResolutions,
            fileMirrors = data.fileMirrors,
        ),
        relatedPosts = RelatedPostsLoader(sourceRegistry, data.fileMirrors),
        comments = CommentsLoader(sourceRegistry),
        creatorProfile = CreatorProfileCoordinator(registry = sourceRegistry,
            follows = com.theoriacodex.data.repository.CreatorFollowsRepository(settingsRepository),
            postResolutions = postResolutions, fileMirrors = data.fileMirrors),
        mediaDurationCoordinator = mediaDurationCoordinator,
        appUsageTracker = appUsageTracker,
        offlineMedia = offlineMedia,
    )

    override val workflows = WorkflowDependencies(
        codexCleaning = CodexCleaningService(codexLikesTransactions),
        likesCodexSync = LikesCodexSyncService(
            transactions = codexLikesTransactions,
            codexRepository = codexRepository,
            likes = likesRepository,
            mirrors = data.fileMirrors,
            canonicalizePost = offlineMedia::withoutOfflineLocations,
        ),
        codexTransfer = CodexTransferService(
            codexRepository = codexRepository,
            transactions = codexLikesTransactions,
            cacheRepository = cacheRepository,
            sourceRegistry = sourceRegistry,
            canonicalizePost = offlineMedia::withoutOfflineLocations,
        ),
        profileBackup = profileBackup,
        autoBackup = autoBackup,
        profileDeletion = profileDeletion,
    )

    override val customSites: CustomSitesController = CustomSitesControllerImpl(
        profiles = siteProfiles,
        credentials = credentialStore,
        prober = SiteProber(siteFamilies, CustomSiteHttpClient(okHttpClient = httpClients.sources)),
        runtime = customSourceRuntime.snapshot,
        settings = settingsRepository,
        library = contentRepository,
        scope = durableStoreScope,
        outages = hostOutages,
        cookieBridge = cookieBridge,
        builtInAgents = builtInAgents,
        clearsChallenge = { url, userAgent -> siteHealthCheck.clearsChallenge(url, mapOf("User-Agent" to userAgent)) },
        runBuiltInSearch = { source ->
            sourceRegistry.adapterFor(source)?.let { adapter ->
                adapter.search(adapter.quickQuery(QuickQueryKind.NEWEST), null)
                true
            } ?: false
        },
    )

    /** Keeps the bridge's allowed hosts equal to the saved sites; [awaitDurableStores] seeds them first. */
    init {
        durableStoreScope.launch {
            siteProfiles.observeProfiles().map(::siteHosts).collect(cookieBridge::updateSiteHosts)
        }
    }

    /** Complete typed-store migration before any route can observe default placeholder state. */
    suspend fun awaitDurableStores() = coroutineScope {
        // The old stored host list; allowed hosts now come from the saved sites.
        appContext.deleteSharedPreferences("browser_verified_sites")
        // Before the app is ready: a request racing an empty list would be challenged and trip a 10-minute outage.
        cookieBridge.updateSiteHosts(siteHosts(siteProfiles.observeProfiles().first()))
        cookieBridge.updateBuiltInAgents(builtInAgents.read())
        val settingsReady = async { settingsRepository.awaitReady() }
        val statisticsReady = async { statisticsStore.awaitReady() }
        val uiRestoreReady = async { uiRestoreRepository.awaitReady() }
        val contentReady = async { awaitContentStore() }
        // The offline catalog scan is independent of profile stores; its snapshot must be warm before jobs resume.
        val offlineCatalogReady = async { offlineStore.load() }
        settingsReady.await()
        statisticsReady.await()
        uiRestoreReady.await()
        contentReady.await()
        profileBackup.recoverPendingRestore()
        profileDeletion.recoverPendingDeletion()
        offlineCatalogReady.await()
        offlineMedia.recoverPendingJobs()
    }

    private suspend fun awaitContentStore() {
        awaitProfileLibraryStores(contentDatabase, storageDirectory, legacyJsonRecoveryRegistry)
    }
}

private fun siteHosts(profiles: List<SiteProfile>): Set<String> = profiles.mapTo(HashSet()) { it.address.host }

/** Matches the built-in client's read timeout; probes keep the custom client's 10 s default. */
private const val CUSTOM_SITE_READ_TIMEOUT_MS = 20_000
