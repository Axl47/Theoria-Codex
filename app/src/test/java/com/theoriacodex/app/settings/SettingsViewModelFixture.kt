package com.theoriacodex.app.settings

import com.theoriacodex.app.sourceauth.CredentialStoreRecoveryState
import com.theoriacodex.app.sourceauth.CredentialStoreUnavailableException
import com.theoriacodex.app.statistics.AppUsageTracker
import com.theoriacodex.app.viewer.ocr.OcrLanguageModelSource
import com.theoriacodex.app.viewer.ocr.OcrLanguageModelState
import com.theoriacodex.data.repository.InMemoryCacheRepository
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.storage.CorruptionRecovery
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun kotlinx.coroutines.test.TestScope.settingsOwner(
    settingsRepository: InMemorySettingsRepository = InMemorySettingsRepository(),
    uiRestoreRepository: InMemoryUiRestoreRepository = InMemoryUiRestoreRepository(),
    accounts: FakeSettingsAccountGateway = FakeSettingsAccountGateway(),
    legacyJsonRecoveries: StateFlow<List<CorruptionRecovery>> = MutableStateFlow(emptyList()),
    availableSources: StateFlow<Set<SourceKey>> = MutableStateFlow(setOf(SourceKey.PIXIV)),
    ocrLanguageModels: OcrLanguageModelSource = FakeOcrLanguageModelSource(),
    likesRepository: com.theoriacodex.data.repository.LikesRepository = InMemoryLikesRepository(),
    codexRepository: com.theoriacodex.data.repository.CodexRepository = InMemoryCodexRepository(),
    profileMutations: SettingsProfileMutations = NoOpSettingsProfileMutations,
): SettingsViewModel {
    val statisticsRepository = InMemoryStatisticsRepository()
    return SettingsViewModel(
        dependencies = SettingsOwnerDependencies(
            settingsRepository = settingsRepository,
            cacheRepository = InMemoryCacheRepository(),
            uiRestoreRepository = uiRestoreRepository,
            likesRepository = likesRepository,
            codexRepository = codexRepository,
            statisticsRepository = statisticsRepository,
            appUsageTracker = AppUsageTracker(
                repository = statisticsRepository,
                scope = backgroundScope,
                elapsedRealtime = { 0L },
                tickIntervalMs = 0L,
            ),
            profileMutations = profileMutations,
            accounts = accounts,
            ocrLanguageModels = ocrLanguageModels,
            availableSources = availableSources,
            legacyJsonRecoveries = legacyJsonRecoveries,
        ),
        coroutineScope = backgroundScope,
    )
}

internal class FakeOcrLanguageModelSource(
    initial: Map<ViewerOcrLanguage, OcrLanguageModelState> =
        ViewerOcrLanguage.entries.associateWith { OcrLanguageModelState.NotDownloaded },
) : OcrLanguageModelSource {
    private val mutableStates = MutableStateFlow(initial)
    override val states: StateFlow<Map<ViewerOcrLanguage, OcrLanguageModelState>> = mutableStates
    val downloads = mutableMapOf<ViewerOcrLanguage, Int>()
    var refreshCount = 0

    override suspend fun refresh() {
        refreshCount += 1
    }

    override suspend fun download(language: ViewerOcrLanguage): Boolean {
        downloads[language] = downloads.getOrDefault(language, 0) + 1
        mutableStates.value = mutableStates.value + (language to OcrLanguageModelState.Ready)
        return true
    }
}

internal class FakeSettingsAccountGateway(
    gelbooru: GelbooruCredentials? = null,
    rule34Xxx: Rule34XxxCredentials? = null,
) : SettingsAccountGateway {
    private val mutableRecovery = MutableStateFlow<CredentialStoreRecoveryState>(
        CredentialStoreRecoveryState.Ready,
    )
    override val recoveryState: StateFlow<CredentialStoreRecoveryState> = mutableRecovery
    private var gelbooruCredential = gelbooru
    private var rule34XxxCredential = rule34Xxx
    var savedGelbooru: GelbooruCredentials? = null
    var gelbooruSaveCount = 0
    var failGelbooruSaveWithRecovery: CredentialStoreRecoveryState? = null

    override suspend fun loadSnapshot(): SettingsAccountSnapshot {
        if (mutableRecovery.value != CredentialStoreRecoveryState.Ready) {
            throw CredentialStoreUnavailableException()
        }
        return SettingsAccountSnapshot(
            pixivStatusLabel = "Not connected",
            pixivConnected = false,
            gelbooruUserId = gelbooruCredential?.userId.orEmpty(),
            gelbooruConfigured = gelbooruCredential != null,
            rule34XxxUserId = rule34XxxCredential?.userId.orEmpty(),
            rule34XxxConfigured = rule34XxxCredential != null,
        )
    }

    override suspend fun startPixivAuthorization(): String = "https://example.test/pixiv"
    override suspend fun disconnectPixiv() = Unit
    override suspend fun currentGelbooruApiKey(): String? = gelbooruCredential?.apiKey
    override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) {
        gelbooruSaveCount += 1
        failGelbooruSaveWithRecovery?.let { recovery ->
            mutableRecovery.value = recovery
            throw CredentialStoreUnavailableException()
        }
        savedGelbooru = credentials
        gelbooruCredential = credentials
    }
    override suspend fun clearGelbooruCredentials() {
        gelbooruCredential = null
    }
    override suspend fun currentRule34XxxApiKey(): String? = rule34XxxCredential?.apiKey
    override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) {
        rule34XxxCredential = credentials
    }
    override suspend fun clearRule34XxxCredentials() {
        rule34XxxCredential = null
    }
    override suspend fun resetAfterReconnectRequired(): Boolean {
        mutableRecovery.value = CredentialStoreRecoveryState.Ready
        gelbooruCredential = null
        rule34XxxCredential = null
        return true
    }
}

private data object NoOpSettingsProfileMutations : SettingsProfileMutations {
    override suspend fun removeProfileData(profileId: String) = Unit
}
