package com.theoriacodex.app.fixtures

import android.content.Context
import com.theoriacodex.app.codex.LikesCodexSyncService
import com.theoriacodex.app.codex.transfer.CodexTransferService
import com.theoriacodex.app.creator.CreatorProfileCoordinator
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.FeatureDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.di.UpdateDependencies
import com.theoriacodex.app.di.WorkflowDependencies
import com.theoriacodex.app.media.BoundedMediaDurationProbe
import com.theoriacodex.app.media.MediaDurationAcquisitionEngine
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.recommend.ForYouCoordinator
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
import com.theoriacodex.data.android.room.RoomCodexLikesRepository
import com.theoriacodex.data.android.room.RoomMediaDurationRepository
import com.theoriacodex.data.android.room.RoomRecentsRepository
import com.theoriacodex.data.android.room.TheoriaRoomDatabase
import com.theoriacodex.data.repository.DataStoreSettingsRepository
import com.theoriacodex.data.repository.DataStoreStatisticsRepository
import com.theoriacodex.data.repository.DataStoreUiRestoreRepository
import com.theoriacodex.data.repository.FileBackedCacheRepository
import com.theoriacodex.data.repository.FileBackedQueryRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.pixiv.PixivAuthApi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Real app graph with test-owned storage and external providers. Never included in release. */
class JourneyAppContainer(
    context: Context,
    storageDirectory: File,
    postsBySource: Map<SourceKey, List<Post>>,
    scope: CoroutineScope,
    pageSize: Int = 20,
    httpClient: SourceHttpClient = JourneyNoNetworkHttpClient,
) : TheoriaAppContainer, AutoCloseable {
    private val appContext = context.applicationContext
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob + Dispatchers.IO)
    val registry = JourneySourceRegistry(postsBySource, pageSize)
    val database = TheoriaRoomDatabase.create(appContext, storageDirectory.resolve("journey.db").absolutePath)
    val content = RoomCodexLikesRepository(database)
    val settings = DataStoreSettingsRepository(storageDirectory, ownerScope)
    private val restore = DataStoreUiRestoreRepository(storageDirectory, ownerScope)
    private val statistics = DataStoreStatisticsRepository(storageDirectory, ownerScope)
    private val durations = RoomMediaDurationRepository(database)
    private val accounts = JourneyAccounts(registry.availableSources())
    private val auth = PixivAuthApi(httpClient)
    private val tags = FileBackedTagSuggestionStore(storageDirectory.resolve("tags.json"))
    private val updateState = FileBackedUpdateStateStore(storageDirectory.resolve("updates.json"))
    private val feed = object : UpdateFeedClient {
        override suspend fun latestMainPrerelease(): Result<RemoteUpdate?> = Result.success(null)
        override suspend fun mainPrereleaseHistory(limit: Int): Result<List<RemoteUpdate>> = Result.success(emptyList())
    }
    override val data = DataDependencies(
        storageDirectory, content, content, FileBackedQueryRepository(storageDirectory),
        RoomRecentsRepository(database), settings, statistics, FileBackedCacheRepository(storageDirectory),
        restore, durations, MutableStateFlow(emptyList()),
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
    override val features = FeatureDependencies(
        search = SearchCoordinator(registry, data.queryRepository, settings, restore, data.recentsRepository,
            statisticsRepository = statistics, tagSuggestionStore = tags),
        forYou = ForYouCoordinator(registry, settings, content, data.recentsRepository,
            statisticsRepository = statistics, tagSuggestionStore = tags),
        relatedPosts = RelatedPostsLoader(registry),
        creatorProfile = CreatorProfileCoordinator(registry),
        mediaDurationCoordinator = MediaDurationCoordinator(
            acquirer = MediaDurationAcquisitionEngine(registry, BoundedMediaDurationProbe(httpClient)),
            durationRepository = durations, parentScope = ownerScope,
        ),
        appUsageTracker = AppUsageTracker(statistics, ownerScope),
    )
    override val workflows = WorkflowDependencies(
        LikesCodexSyncService(content, content), CodexTransferService(content, content, data.cacheRepository, registry),
    )
    suspend fun awaitReady() {
        settings.awaitReady()
        restore.awaitReady()
        statistics.awaitReady()
        settings.setEnabledSources(registry.availableSources())
        settings.setResolveUnknownAnimatedDurations(false)
    }
    /** Await DataStore ownership release before another graph opens the same fixture files. */
    suspend fun shutdown() = withContext(Dispatchers.IO) {
        features.mediaDurationCoordinator.close()
        tags.close()
        ownerJob.cancelAndJoin()
        database.close()
    }

    override fun close() {
        runBlocking { shutdown() }
    }
}
