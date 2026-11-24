package com.theoriacodex.app.fixtures

import android.content.Context
import com.theoriacodex.app.codex.LikesCodexSyncService
import com.theoriacodex.app.codex.transfer.CodexTransferService
import com.theoriacodex.app.creator.CreatorProfileCoordinator
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.FeatureDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.network.AndroidBrowserCookieStore
import com.theoriacodex.app.customsites.BuiltInAgentStore
import com.theoriacodex.app.customsites.CustomSitesController
import com.theoriacodex.app.customsites.SiteCookieBridge
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.app.di.CustomSitesControllerImpl
import com.theoriacodex.app.di.CustomSourceRuntime
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.sourceauth.InMemorySiteCredentialsStore
import com.theoriacodex.data.repository.InMemorySiteProfileRepository
import com.theoriacodex.sources.custom.SiteFamilyRegistry
import com.theoriacodex.sources.custom.SiteProber
import com.theoriacodex.app.di.UpdateDependencies
import com.theoriacodex.app.di.WorkflowDependencies
import com.theoriacodex.app.di.appPostDownloads
import com.theoriacodex.app.media.BoundedMediaDurationProbe
import com.theoriacodex.app.media.MediaDurationAcquisitionEngine
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.media.OfflineAwareCacheRepository
import com.theoriacodex.app.media.OfflineMediaCoordinator
import com.theoriacodex.app.media.downloadablePages
import com.theoriacodex.app.media.validateOfflineMediaFile
import com.theoriacodex.app.recommend.ForYouCoordinator
import com.theoriacodex.app.recommend.shareTagAffinityInputs
import com.theoriacodex.app.comments.CommentsLoader
import com.theoriacodex.app.related.RelatedPostsLoader
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.sourceauth.InMemoryPixivPkceSessionStore
import com.theoriacodex.app.sourceauth.PixivPkceController
import com.theoriacodex.app.statistics.AppUsageTracker
import com.theoriacodex.app.update.ApkDownloadManager
import com.theoriacodex.app.update.ApkInstaller
import com.theoriacodex.app.update.ApkUpdateValidator
import com.theoriacodex.app.update.FileBackedUpdateStateStore
import com.theoriacodex.app.update.RemoteUpdate
import com.theoriacodex.app.update.StartupUpdater
import com.theoriacodex.app.update.UpdateFeedClient
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.di.ListingPictures
import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.data.android.room.RoomCodexLikesRepository
import com.theoriacodex.data.android.room.RoomForYouVerdictRepository
import com.theoriacodex.data.android.room.RoomVisualSignatureRepository
import com.theoriacodex.data.android.room.RoomMediaDurationRepository
import com.theoriacodex.data.android.room.RoomRecentsRepository
import com.theoriacodex.data.android.room.TheoriaRoomDatabase
import com.theoriacodex.data.repository.DataStoreSettingsRepository
import com.theoriacodex.data.repository.DataStoreStatisticsRepository
import com.theoriacodex.data.repository.DataStoreUiRestoreRepository
import com.theoriacodex.data.storage.closeDataStores
import com.theoriacodex.data.storage.newDataStoreScope
import com.theoriacodex.data.repository.FileBackedCacheRepository
import com.theoriacodex.data.repository.FileBackedQueryRepository
import com.theoriacodex.data.repository.FileBackedReadingPositionRepository
import com.theoriacodex.data.repository.FileBackedGalleryReadingModeRepository
import com.theoriacodex.data.repository.FileBackedSavedSearchRepository
import com.theoriacodex.data.repository.ProfileBackupService
import com.theoriacodex.data.repository.ProfileDeletionService
import com.theoriacodex.data.repository.ProfileLibraryWriteGuard
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.android.room.RoomProfileBackupStore
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.pixiv.PixivAuthApi
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow

class JourneyAppContainer(
    context: Context,
    storageDirectory: File,
    postsBySource: Map<SourceKey, List<Post>>,
    scope: CoroutineScope,
    pageSize: Int = 20,
    httpClient: SourceHttpClient = JourneyNoNetworkHttpClient,
    providerShapedSources: Set<SourceKey> = emptySet(),
) : TheoriaAppContainer, AutoCloseable {
    private val appContext = context.applicationContext
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob + Dispatchers.IO)
    override val applicationScope: CoroutineScope get() = ownerScope
    private val fileMirrors = FileMirrorIndex()
    private val listingPictures = ListingPictures(appContext, ownerScope, fileMirrors, budgetMs = 10_000)
    val registry = JourneySourceRegistry(
        postsBySource, pageSize,
        providerShapedSources.associateWith { ProviderShapedSource(postsBySource.getValue(it), httpClient) },
        annotateListing = listingPictures::annotate,
    )
    val database = TheoriaRoomDatabase.create(appContext, storageDirectory.resolve("journey.db").absolutePath)
    private val profileWriteGuard = ProfileLibraryWriteGuard()
    val content = RoomCodexLikesRepository(database, writeGuard = profileWriteGuard)
    private val storeScope = newDataStoreScope()
    val settings = DataStoreSettingsRepository(storageDirectory, storeScope, profileWriteGuard = profileWriteGuard)
    private val restore = DataStoreUiRestoreRepository(storageDirectory, storeScope)
    private val statistics = DataStoreStatisticsRepository(storageDirectory, storeScope)
    private val durations = RoomMediaDurationRepository(database)
    private val accounts = JourneyAccounts(registry.availableSources())
    private val siteProfiles = InMemorySiteProfileRepository()
    private val siteKeys = InMemorySiteCredentialsStore()
    private val auth = PixivAuthApi(httpClient)
    private val tags = FileBackedTagSuggestionStore(storageDirectory.resolve("tags.json"))
    private val updateState = FileBackedUpdateStateStore(storageDirectory.resolve("updates.json"))
    private val feed = object : UpdateFeedClient {
        override suspend fun latestMainPrerelease(): Result<RemoteUpdate?> = Result.success(null)
        override suspend fun mainPrereleaseHistory(limit: Int): Result<List<RemoteUpdate>> = Result.success(emptyList())
    }
    private val ready = CompletableDeferred<Unit>()
    private val offline = OfflineMediaCoordinator(
        store = OfflineMediaStore(storageDirectory.resolve("offline-media")),
        scope = ownerScope,
        resolve = { post -> checkNotNull(registry.adapterFor(post.id.source)?.resolvePost(post.id)) },
        selectMedia = { post -> post.downloadablePages },
        acquire = { _, media, output ->
            withContext(Dispatchers.IO) {
                val source = File(checkNotNull(media.localPath) { "Fixture offline media must be local" })
                check(source.isFile && source.length() > 0L) { "Fixture media is missing" }
                source.copyTo(output, overwrite = true)
                validateOfflineMediaFile(output)
            }
        },
    )
    override val data = DataDependencies(
        storageDirectory, content, content, FileBackedQueryRepository(storageDirectory),
        RoomRecentsRepository(database), settings, statistics,
        OfflineAwareCacheRepository(FileBackedCacheRepository(storageDirectory), offline),
        restore, durations, MutableStateFlow(emptyList()),
        readingPositions = FileBackedReadingPositionRepository(storageDirectory, writeScope = ownerScope, writeDelay = FLUSH_ONLY_WRITE_DELAY),
        galleryReadingModes = FileBackedGalleryReadingModeRepository(storageDirectory),
        savedSearches = FileBackedSavedSearchRepository(storageDirectory),
        forYouVerdicts = RoomForYouVerdictRepository(database, profileWriteGuard),
        visualSignatures = RoomVisualSignatureRepository(database),
        fileMirrors = fileMirrors,
    )
    override val sources = SourceDependencies(
        httpClient, accounts, auth, PixivPkceController(auth, accounts, InMemoryPixivPkceSessionStore()),
        PixivUgoiraClient(accounts, httpClient, archiveDirectory = storageDirectory.resolve("ugoira")),
        registry, accounts.availableSources,
    )
    override val updates = UpdateDependencies(
        updateState, feed, StartupUpdater(appContext, feed, ApkDownloadManager(appContext, "fixture.apk"),
            ApkUpdateValidator(appContext), object : ApkInstaller {
                override fun launchInstaller(apkFile: File): Result<Unit> = error("Fixture cannot install APKs")
            }, updateState, updateCheckTimeoutMs = 1_000L),
    )
    private val tagAffinity = shareTagAffinityInputs(ownerScope, settings, content, content, data.savedSearches, statistics)
    override val features = FeatureDependencies(
        search = SearchCoordinator(registry, data.queryRepository, settings, restore, data.recentsRepository,
            statisticsRepository = statistics, tagSuggestionStore = tags),
        forYou = ForYouCoordinator(registry, statistics, tagAffinity::forProfile, tagSuggestionStore = tags),
        tagAffinity = tagAffinity,
        relatedPosts = RelatedPostsLoader(registry),
        comments = CommentsLoader(registry),
        creatorProfile = CreatorProfileCoordinator(registry),
        mediaDurationCoordinator = MediaDurationCoordinator(
            acquirer = MediaDurationAcquisitionEngine(registry, BoundedMediaDurationProbe(httpClient)),
            durationRepository = durations, parentScope = ownerScope,
        ),
        appUsageTracker = AppUsageTracker(statistics, ownerScope),
        offlineMedia = offline,
        postDownloads = appPostDownloads(appContext, storageDirectory.resolve("download_jobs.json"), ownerScope, settings, sources,
            ready::await),
    )
    override val workflows = WorkflowDependencies(
        com.theoriacodex.app.codex.CodexCleaningService(content),
        LikesCodexSyncService(content, content, content, canonicalizePost = offline::withoutOfflineLocations),
        CodexTransferService(content, content, data.cacheRepository, registry, canonicalizePost = offline::withoutOfflineLocations),
        profileDeletion = ProfileDeletionService(settings, content,
            storageDirectory.resolve("pending_profile_deletion.json"), profileWriteGuard),
        profileBackup = ProfileBackupService(settings, RoomProfileBackupStore(database), data.savedSearches,
            data.readingPositions, storageDirectory.resolve("pending_profile_restore.json"), siteProfiles = siteProfiles),
    )
    override val customSites: CustomSitesController = CustomSitesControllerImpl(
        profiles = siteProfiles,
        credentials = siteKeys,
        prober = SiteProber(SiteFamilyRegistry.default(), httpClient),
        runtime = CustomSourceRuntime(siteProfiles, siteKeys, SiteFamilyRegistry.default(), httpClient, ownerScope).snapshot,
        settings = settings,
        library = content,
        scope = ownerScope,
        outages = HostOutageTracker(),
        cookieBridge = SiteCookieBridge(AndroidBrowserCookieStore),
        builtInAgents = object : BuiltInAgentStore {
            override fun read(): Map<String, String> = emptyMap()
            override fun save(host: String, userAgent: String) = Unit
        },
        clearsChallenge = { _, _ -> true },
        runBuiltInSearch = { true },
    )
    suspend fun awaitReady() {
        settings.awaitReady()
        restore.awaitReady()
        statistics.awaitReady()
        workflows.profileBackup.recoverPendingRestore()
        workflows.profileDeletion.recoverPendingDeletion()
        settings.setEnabledSources(registry.availableSources())
        settings.setResolveUnknownAnimatedDurations(false)
        ready.complete(Unit)
    }
    suspend fun shutdown() = withContext(Dispatchers.IO) {
        try {
            data.flushPendingWrites()
            features.mediaDurationCoordinator.close()
            tags.close()
            ownerJob.cancelAndJoin()
            storeScope.closeDataStores()
        } finally {
            database.close()
        }
    }

    override fun close() {
        runBlocking { shutdown() }
    }
}

private val FLUSH_ONLY_WRITE_DELAY = 1.hours
