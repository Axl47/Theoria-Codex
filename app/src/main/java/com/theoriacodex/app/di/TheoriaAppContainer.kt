package com.theoriacodex.app.di

import com.theoriacodex.app.network.AppHttpClients
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.theoriacodex.app.backup.AndroidAutoBackupFolder
import com.theoriacodex.app.backup.AutoBackupWorkflow
import com.theoriacodex.app.backup.SharedPreferencesAutoBackupStore
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.creator.CreatorProfileCoordinator
import com.theoriacodex.app.customsites.CustomSitesController
import com.theoriacodex.app.extensions.ExtensionSites
import com.theoriacodex.app.extensions.ExtensionSourceHost
import com.theoriacodex.app.customsites.SharedPreferencesBuiltInAgentStore
import com.theoriacodex.app.customsites.SiteCookieBridge
import com.theoriacodex.app.media.BoundedMediaDurationProbe
import com.theoriacodex.app.media.MediaDurationAcquisitionEngine
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.media.OfflineAwareCacheRepository
import com.theoriacodex.app.media.OfflineMediaAcquisition
import com.theoriacodex.app.media.OfflineMediaCoordinator
import com.theoriacodex.app.media.PostDownloads
import com.theoriacodex.app.media.codexOfflineOwner
import com.theoriacodex.app.media.pictureHashAt
import com.theoriacodex.app.series.ChapterPictures
import com.theoriacodex.app.codex.LikesCodexSyncService
import com.theoriacodex.app.codex.transfer.CodexTransferService
import com.theoriacodex.app.recommend.ForYouCoordinator
import com.theoriacodex.app.recommend.PictureEvidence
import com.theoriacodex.app.recommend.SharedTagAffinity
import com.theoriacodex.app.recommend.shareTagAffinityInputs
import com.theoriacodex.app.comments.CommentsLoader
import com.theoriacodex.app.comments.CommentsLoading
import com.theoriacodex.app.related.RelatedPostsLoader
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.search.loadSeedTagSuggestions
import com.theoriacodex.app.statistics.AppUsageTracker
import com.theoriacodex.app.source.displayName
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
import com.theoriacodex.data.repository.GalleryReadingModeRepository
import com.theoriacodex.data.repository.FileBackedGalleryReadingModeRepository
import com.theoriacodex.data.repository.InMemoryGalleryReadingModeRepository
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
import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.android.room.RoomCodexLikesRepository
import com.theoriacodex.data.android.room.RoomForYouVerdictRepository
import com.theoriacodex.data.android.room.RoomVisualSignatureRepository
import com.theoriacodex.data.repository.ForYouVerdictRepository
import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.app.media.LibraryPictureBackfill
import com.theoriacodex.app.vision.AppPictures
import com.theoriacodex.app.vision.appPictures
import com.theoriacodex.app.vision.keepLibraryPicturesScheduled
import com.theoriacodex.domain.vision.PictureTags
import com.theoriacodex.data.android.room.RoomMediaDurationRepository
import com.theoriacodex.data.android.room.RoomRecentsRepository
import com.theoriacodex.data.android.room.RoomSeriesRepository
import com.theoriacodex.data.android.room.RoomProfileBackupStore
import com.theoriacodex.data.android.room.openProfileLibrary
import com.theoriacodex.data.android.room.TheoriaRoomDatabase
import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.series.SourceAvailability
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
import com.theoriacodex.app.library.LibrarySectionStore
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.theoriacodex.app.series.keepSeriesUpdatesScheduled
import com.theoriacodex.app.series.SeriesDownloadScheduler
import com.theoriacodex.app.series.clearIdleWaitingNotices
import com.theoriacodex.app.series.downloadChapterPage
import com.theoriacodex.data.repository.SeriesDownloads

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
    val recoveredStores: StateFlow<List<CorruptionRecovery>>,
    val readingPositions: ReadingPositionRepository = InMemoryReadingPositionRepository(),
    val galleryReadingModes: GalleryReadingModeRepository = InMemoryGalleryReadingModeRepository(),
    val savedSearches: SavedSearchRepository = InMemorySavedSearchRepository(),
    /** This session's copies of one file across sites; feeds teach it what they list. */
    val fileMirrors: FileMirrorIndex = FileMirrorIndex(),
    val librarySections: LibrarySectionStore = LibrarySectionStore(uiRestoreRepository),
    /** Every For You swipe, kept for a later taste model; nothing reads it yet. */
    val forYouVerdicts: ForYouVerdictRepository,
    /** The Library's picture hashes, so a repost of a liked or saved post elsewhere counts as one. */
    val visualSignatures: VisualSignatureRepository,
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
    /** The active profile's tag affinity, built once for the app session; see [shareTagAffinityInputs]. */
    val tagAffinity: SharedTagAffinity,
    val relatedPosts: RelatedPostsLoader,
    val comments: CommentsLoading,
    val creatorProfile: CreatorProfileCoordinator,
    val mediaDurationCoordinator: MediaDurationCoordinator,
    val appUsageTracker: AppUsageTracker,
    val offlineMedia: OfflineMediaCoordinator,
    /** The one download queue every Activity instance uses (see [appPostDownloads]). */
    val postDownloads: PostDownloads,
    /** What the opt-in picture tagger predicts; [PictureTags.None] while there is no model or no tagger. */
    val pictureTags: PictureTags = PictureTags.None,
)

data class WorkflowDependencies(
    val codexCleaning: CodexCleaningService,
    val likesCodexSync: LikesCodexSyncService,
    val codexTransfer: CodexTransferService,
    val profileBackup: ProfileBackupService,
    val autoBackup: AutoBackupWorkflow? = null,
    val profileDeletion: ProfileDeletionService,
)

interface TheoriaAppContainer {
    val data: DataDependencies
    val sources: SourceDependencies
    val updates: UpdateDependencies
    val features: FeatureDependencies
    val workflows: WorkflowDependencies

    /** Settings → Sites: user-added booru sites. */
    val customSites: CustomSitesController

    /** The Series area; null in a container with no extension host. */
    val series: SeriesDependencies? get() = null

    /** The extension sources' sites and client, for their browser checks; null with no extension host. */
    val extensionSites: ExtensionSites? get() = null

    /** The Library's picture indexing and the opt-in picture tagger; null in a container without them. */
    val pictures: AppPictures? get() = null

    /** Lives as long as the process: work that must finish after the screen or Activity that started it. */
    val applicationScope: CoroutineScope
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
    private val hostOutages: HostOutageTracker,
    private val networkOnline: StateFlow<Boolean>,
    private val extensionHost: ExtensionSourceHost? = null,
) : TheoriaAppContainer {
    private val appContext = context.applicationContext
    private val storageDirectory = File(appContext.filesDir, "theoria_codex")
    private val durableStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val applicationScope: CoroutineScope get() = durableStoreScope
    private val offlineStore = OfflineMediaStore(File(storageDirectory, "offline_media"))
    private val fileStoreRecoveryRegistry = FileStoreRecoveryRegistry()
    // Opened before anything starts background work: a pins file from a newer build fails construction, and a retry
    // builds a new container while this one's stores would still be open.
    private val savedSearches = FileBackedSavedSearchRepository(
        storageDirectory, recoveryRegistry = fileStoreRecoveryRegistry, sourceName = { it.displayName() },
    )
    private val tagSuggestionStore = FileBackedTagSuggestionStore(
        storeFile = File(storageDirectory, "tag_suggestions.json"),
        seedData = loadSeedTagSuggestions(appContext),
        recoveryRegistry = fileStoreRecoveryRegistry,
    )
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
    private val siteProfiles = DataStoreSiteProfileRepository(
        baseDirectory = storageDirectory,
        scope = durableStoreScope,
        recoveryRegistry = fileStoreRecoveryRegistry,
    )
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
    /** Opened at the end of [awaitDurableStores]: restored downloads and offline jobs wait on it. */
    private val sourceReadiness = SourceReadiness(accountStore::refreshAvailability) { customSourceRuntime.awaitFirstBuild() }
    private val fileMirrors = FileMirrorIndex()
    private val listingPictures = ListingPictures(appContext, durableStoreScope, fileMirrors)
    private val sourceRegistry = CompositeSourceAdapterRegistry(
        potentialBuiltIns = allPotentialSourceRegistry,
        availableBuiltIns = accountStore.availableSources,
        custom = customSourceRuntime.snapshot,
        annotateListing = listingPictures::annotate,
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
    private val visualSignatures = RoomVisualSignatureRepository(contentDatabase)
    override val pictures = appPictures(
        appContext, httpClients.shared, visualSignatures, LibraryPictureBackfill(visualSignatures, listingPictures.hasher::hash),
        pixivUgoiraClient, offlineStore::find, { id -> sourceRegistry.adapterFor(id.source)?.resolvePost(id) }, durableStoreScope,
    )
    private val codexRepository: CodexRepository = contentRepository
    private val likesRepository: LikesRepository = contentRepository
    private val codexLikesTransactions: CodexLikesTransactions = contentRepository
    private val queryRepository = FileBackedQueryRepository(
        storageDirectory,
        recoveryRegistry = fileStoreRecoveryRegistry,
    )
    private val recentsRepository = RoomRecentsRepository(contentDatabase)
    private val settingsRepository = DataStoreSettingsRepository(
        baseDirectory = storageDirectory,
        scope = durableStoreScope,
        recoveryRegistry = fileStoreRecoveryRegistry,
        profileWriteGuard = profileWriteGuard,
    )
    private val profileDeletion = ProfileDeletionService(settingsRepository, contentRepository,
        File(storageDirectory, "pending_profile_deletion.json"), profileWriteGuard)
    private val statisticsStore = DataStoreStatisticsRepository(
        baseDirectory = storageDirectory,
        scope = durableStoreScope,
        recoveryRegistry = fileStoreRecoveryRegistry,
    )
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
        awaitSources = sourceReadiness::await,
    )
    private val cacheRepository = OfflineAwareCacheRepository(FileBackedCacheRepository(storageDirectory), offlineMedia)
    private val readingPositions = FileBackedReadingPositionRepository(storageDirectory)
    // Before the backup service, which adds an imported backup's extension repos.
    override val series: SeriesDependencies? = extensionHost?.let { host ->
        val choices = DeviceExtensionChoices.on(appContext)
        host.bind(choices)
        val repository = RoomSeriesRepository(contentDatabase)
        // Neither Android's backup nor device transfer carries the queue (noBackupFilesDir) or the offline copies.
        val downloads = SeriesDownloads(
            store = offlineStore,
            queueFile = File(appContext.noBackupFilesDir, "series_download_queue.json"),
            repository = repository,
            source = host::source,
            covers = host.covers,
            // The client that carries the extension interceptor, as the Viewer's image loader does.
            fetch = { url, file -> downloadChapterPage(url, file, httpClients.downloads) },
            scheduler = SeriesDownloadScheduler(appContext) { settingsRepository.observeSettings().first().cache },
            deleteAfterReading = { settingsRepository.observeSettings().first().seriesDeleteAfterReading },
        )
        SeriesDependencies(
            repository, host::sources, host::source, host.extensions, host.repos(httpClients.shared), downloads, scope = durableStoreScope, covers = host.covers,
            pictures = ChapterPictures(repository) { source, page -> (page.localPath ?: page.url)?.let { pictureHashAt(appContext, it, source, cachedOnly = true) } },
            log = { Log.i("SeriesReader", it) },
            loadedSources = host::loadedSources,
        )
    }

    private val profileBackup = ProfileBackupService(
        settings = settingsRepository,
        store = RoomProfileBackupStore(contentDatabase),
        savedSearches = savedSearches,
        readingPositions = readingPositions,
        journalFile = File(storageDirectory, "pending_profile_restore.json"),
        statistics = statisticsStore,
        writeGuard = profileWriteGuard,
        siteProfiles = siteProfiles,
        extensionRepos = series?.repos,
        extensionSources = { extensionHost?.availability() ?: SourceAvailability() },
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
        recoveryRegistry = fileStoreRecoveryRegistry,
    )
    private val appUsageTracker = AppUsageTracker(
        repository = statisticsRepository,
        scope = durableStoreScope,
    )
    private val updateStateStore = FileBackedUpdateStateStore(
        file = File(storageDirectory, "update_state.json"),
        recoveryRegistry = fileStoreRecoveryRegistry,
    )
    private val updateFeedClient = GitHubReleaseFeedClient(
        owner = BuildConfig.UPDATE_REPO_OWNER,
        repo = BuildConfig.UPDATE_REPO_NAME,
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
        recoveredStores = fileStoreRecoveryRegistry.recoveries,
        readingPositions = readingPositions,
        galleryReadingModes = FileBackedGalleryReadingModeRepository(storageDirectory),
        savedSearches = savedSearches,
        fileMirrors = fileMirrors,
        forYouVerdicts = RoomForYouVerdictRepository(contentDatabase, profileWriteGuard),
        visualSignatures = visualSignatures,
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

    private val tagAffinity = shareTagAffinityInputs(
        durableStoreScope, settingsRepository, likesRepository, codexRepository, savedSearches, statisticsRepository,
        predicted = pictures.tagger.library,
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
            statisticsRepository = statisticsRepository,
            // Deleting the model plans lanes again at once, before the shared inputs drop its evidence.
            tagAffinity = { profile ->
                tagAffinity.forProfile(profile).let { if (pictures.tagger.ready.value) it else it.copy(pictures = PictureEvidence()) }
            },
            tagSuggestionStore = tagSuggestionStore,
            postResolutions = postResolutions,
            fileMirrors = data.fileMirrors,
        ),
        tagAffinity = tagAffinity,
        relatedPosts = RelatedPostsLoader(sourceRegistry, data.fileMirrors),
        comments = CommentsLoader(sourceRegistry),
        creatorProfile = CreatorProfileCoordinator(registry = sourceRegistry,
            follows = com.theoriacodex.data.repository.CreatorFollowsRepository(settingsRepository),
            postResolutions = postResolutions, fileMirrors = data.fileMirrors),
        mediaDurationCoordinator = mediaDurationCoordinator,
        appUsageTracker = appUsageTracker,
        offlineMedia = offlineMedia,
        postDownloads = appPostDownloads(appContext, File(storageDirectory, "download_jobs.json"), durableStoreScope,
            settingsRepository, sources, sourceReadiness::await),
        pictureTags = pictures.tagger,
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
            keepOffline = { codexId, posts -> offlineMedia.keepOffline(codexOfflineOwner(codexId), posts) },
        ),
        profileBackup = profileBackup,
        autoBackup = autoBackup,
        profileDeletion = profileDeletion,
    )

    override val extensionSites: ExtensionSites? get() = extensionHost

    /** Keeps the new-chapter check scheduled; started by [awaitDurableStores], once. */
    private var seriesScheduling: Job? = null

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
                adapter.search(Query.newest(adapter.sourceKey), null)
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

    /**
     * Complete typed-store migration before any route can observe default placeholder state. Every DataStore must be
     * awaited here: one that cannot open throws, which shows the startup failure screen, while collectors started
     * during construction wait for it instead of crashing. Retry calls this again on the same container.
     */
    suspend fun awaitDurableStores() = coroutineScope {
        // The old stored host list; allowed hosts now come from the saved sites.
        appContext.deleteSharedPreferences("browser_verified_sites")
        // Before the app is ready: a request racing an empty list would be challenged and trip a 10-minute outage.
        siteProfiles.awaitReady()
        cookieBridge.updateSiteHosts(siteHosts(siteProfiles.observeProfiles().first()))
        cookieBridge.updateBuiltInAgents(builtInAgents.read())
        val settingsReady = async { settingsRepository.awaitReady() }
        val statisticsReady = async { statisticsStore.awaitReady() }
        val uiRestoreReady = async { uiRestoreRepository.awaitReady() }
        val contentReady = async { openProfileLibrary(contentDatabase) }
        // The offline catalog scan is independent of profile stores; its snapshot must be warm before jobs resume.
        val offlineCatalogReady = async { offlineStore.load() }
        settingsReady.await()
        statisticsReady.await()
        uiRestoreReady.await()
        contentReady.await()
        profileBackup.recoverPendingRestore()
        profileDeletion.recoverPendingDeletion()
        offlineCatalogReady.await()
        // Registers the saved offline queue before any screen can add to it; its jobs wait on sourceReadiness.
        offlineMedia.recoverPendingJobs()
        sourceReadiness.open()
        if (series != null) {
            durableStoreScope.launch {
                // Chapters downloaded before they moved into the offline store; no release had them.
                // Remove once v1.10.0 has shipped.
                File(appContext.noBackupFilesDir, "series_downloads").deleteRecursively()
                runCatchingPreservingCancellation { forgetLegacyExtensionData(appContext, settingsRepository) }
                    .onFailure { Diagnostics.report("extensions", "Old extension data could not be removed", it) }
            }
        }
        // Only now: watching followed series opens Room, which must first get the chance to fail into the startup screen.
        if (seriesScheduling == null) {
            seriesScheduling = series?.let { series ->
                durableStoreScope.launch {
                    runCatchingPreservingCancellation { series.downloads.resume() }
                        .onFailure { Diagnostics.report("series", "Chapter downloads could not resume", it) }
                }
                durableStoreScope.launch {
                    val network = settingsRepository.observeSettings().map { it.cache.downloadsOverMetered to it.cache.downloadsOverRoaming }
                    runCatchingPreservingCancellation { series.downloads.rescheduleOnChanges(series.extensions.revision, network) }
                        .onFailure { Diagnostics.report("series", "Chapter downloads stopped following source and network changes", it) }
                }
                durableStoreScope.launch {
                    runCatchingPreservingCancellation { series.downloads.queuedLanes().collect { clearIdleWaitingNotices(appContext, it) } }
                        .onFailure { Diagnostics.report("series", "Waiting download notices stopped following the queue", it) }
                }
                durableStoreScope.launch {
                    keepSeriesUpdatesScheduled(appContext, series.repository.observeHasFollowed(), settingsRepository.observeSettings())
                }
            }
        }
        durableStoreScope.launch {
            runCatchingPreservingCancellation { keepLibraryPicturesScheduled(appContext, pictures) }
                .onFailure { Diagnostics.report("similarity", "The Library's picture indexing could not be scheduled", it) }
        }
        durableStoreScope.launch {
            runCatchingPreservingCancellation { pictures.tags.writeThrough() }
                .onFailure { Diagnostics.report("vision", "This session's picture tags could not be stored", it) }
        }
        // Off the startup path: searched-tag suggestions start from what Recents remembers.
        durableStoreScope.launch {
            runCatchingPreservingCancellation {
                statisticsRepository.seedSearchedTags(recentsRepository.observeSearches().first())
            }
        }
    }
}

private fun siteHosts(profiles: List<SiteProfile>): Set<String> = profiles.mapTo(HashSet()) { it.address.host }

/** Matches the built-in client's read timeout; probes keep the custom client's 10 s default. */
private const val CUSTOM_SITE_READ_TIMEOUT_MS = 20_000
