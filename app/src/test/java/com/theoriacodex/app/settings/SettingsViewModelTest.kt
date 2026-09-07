package com.theoriacodex.app.settings

import com.theoriacodex.app.sourceauth.CredentialStoreRecoveryState
import com.theoriacodex.app.viewer.ocr.OcrLanguageModelState
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.storage.CorruptionRecovery
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.data.repository.ViewerOcrLanguage
import com.theoriacodex.sources.credentials.GelbooruCredentials
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test
    fun `expansion restores as one keyed state and persists each independent update`() = runTest {
        val restoreRepository = InMemoryUiRestoreRepository().apply {
            setSettingsSectionExpansion(
                mapOf(
                    SettingsSectionKey.SOURCE_ACCOUNTS.name to false,
                    SettingsSectionKey.UPDATES.name to true,
                )
            )
        }
        val owner = settingsOwner(uiRestoreRepository = restoreRepository)
        runCurrent()

        assertFalse(owner.state.value.sectionExpansion[SettingsSectionKey.SOURCE_ACCOUNTS])
        assertTrue(owner.state.value.sectionExpansion[SettingsSectionKey.UPDATES])
        assertFalse(owner.state.value.sectionExpansion[SettingsSectionKey.STORAGE_AND_CACHING])

        owner.onAction(
            SettingsAction.SetSectionExpanded(SettingsSectionKey.UPDATES, expanded = false),
        )
        runCurrent()

        val persisted = restoreRepository.getSettingsSectionExpansion()
        assertFalse(persisted.getValue(SettingsSectionKey.SOURCE_ACCOUNTS.name))
        assertFalse(persisted.getValue(SettingsSectionKey.UPDATES.name))
        assertFalse(persisted.getValue(SettingsSectionKey.STORAGE_AND_CACHING.name))
    }

    @Test
    fun `first open starts collapsed without overriding explicit restored choices`() = runTest {
        val firstOpen = settingsOwner()
        runCurrent()

        assertTrue(SettingsSectionKey.entries.none { firstOpen.state.value.sectionExpansion[it] })

        val restored = settingsOwner(
            uiRestoreRepository = InMemoryUiRestoreRepository().apply {
                setSettingsSectionExpansion(
                    SettingsSectionKey.entries.associate { section ->
                        section.name to (section == SettingsSectionKey.UPDATES)
                    },
                )
            },
        )
        runCurrent()

        assertTrue(restored.state.value.sectionExpansion[SettingsSectionKey.UPDATES])
        assertFalse(restored.state.value.sectionExpansion[SettingsSectionKey.SOURCE_ACCOUNTS])
    }

    @Test
    fun `preference actions mutate repositories and return through owner state`() = runTest {
        val settingsRepository = InMemorySettingsRepository()
        val owner = settingsOwner(settingsRepository = settingsRepository)
        runCurrent()

        owner.onAction(SettingsAction.SetCacheFullImageOnSave(true))
        owner.onAction(SettingsAction.SetResolveUnknownAnimatedDurations(false))
        owner.onAction(SettingsAction.SetEnabledSources(setOf(SourceKey.PIXIV, SourceKey.IWARA)))
        runCurrent()

        assertTrue(owner.state.value.settings.cache.cacheFullImageOnSave)
        assertFalse(owner.state.value.settings.contentFilters.resolveUnknownAnimatedDurations)
        assertEquals(setOf(SourceKey.PIXIV), owner.state.value.settings.runtime.enabledSources)
    }

    @Test
    fun `OCR language downloads enable only ready requested models`() = runTest {
        val settingsRepository = InMemorySettingsRepository()
        val models = FakeOcrLanguageModelSource(
            initial = mapOf(
                ViewerOcrLanguage.JAPANESE to OcrLanguageModelState.NotDownloaded,
                ViewerOcrLanguage.CHINESE to OcrLanguageModelState.Ready,
                ViewerOcrLanguage.KOREAN to OcrLanguageModelState.NotDownloaded,
            ),
        )
        val owner = settingsOwner(settingsRepository = settingsRepository, ocrLanguageModels = models)
        runCurrent()

        owner.onAction(SettingsAction.SetOcrLanguageEnabled(ViewerOcrLanguage.JAPANESE, true))
        owner.onAction(SettingsAction.SetOcrLanguageEnabled(ViewerOcrLanguage.CHINESE, true))
        owner.onAction(SettingsAction.DownloadOcrLanguage(ViewerOcrLanguage.JAPANESE))
        runCurrent()

        assertEquals(1, models.downloads[ViewerOcrLanguage.JAPANESE])
        assertEquals(
            setOf(ViewerOcrLanguage.JAPANESE, ViewerOcrLanguage.CHINESE),
            settingsRepository.observeSettings().first().viewer.enabledOcrLanguages,
        )
    }

    @Test
    fun `entering Settings refreshes OCR model availability`() = runTest {
        val models = FakeOcrLanguageModelSource()
        val owner = settingsOwner(ocrLanguageModels = models)
        runCurrent()

        owner.onAction(SettingsAction.SettingsEntered)
        runCurrent()

        assertEquals(1, models.refreshCount)
    }

    @Test
    fun `late source availability appears and toggle persists from the live source set`() = runTest {
        val settingsRepository = InMemorySettingsRepository()
        val availableSources = MutableStateFlow(setOf(SourceKey.PIXIV))
        val owner = settingsOwner(
            settingsRepository = settingsRepository,
            availableSources = availableSources,
        )
        runCurrent()
        assertEquals(listOf(SourceKey.PIXIV), owner.state.value.availableSources)

        availableSources.value = setOf(SourceKey.PIXIV, SourceKey.RULE34XXX)
        owner.onAction(
            SettingsAction.SetEnabledSources(setOf(SourceKey.PIXIV, SourceKey.RULE34XXX)),
        )
        runCurrent()

        assertEquals(
            listOf(SourceKey.PIXIV, SourceKey.RULE34XXX),
            owner.state.value.availableSources,
        )
        assertEquals(
            setOf(SourceKey.PIXIV, SourceKey.RULE34XXX),
            settingsRepository.observeSettings().first().runtime.enabledSources,
        )
        assertEquals(
            setOf(SourceKey.PIXIV, SourceKey.RULE34XXX),
            owner.state.value.settings.runtime.enabledSources,
        )
    }

    @Test
    fun `verified legacy recovery is presented by the Settings owner`() = runTest {
        val recoveries = MutableStateFlow<List<CorruptionRecovery>>(emptyList())
        val owner = settingsOwner(legacyJsonRecoveries = recoveries)
        runCurrent()

        val recovery = CorruptionRecovery(
            reason = "query_store.json contains malformed JSON",
            backupPath = "/data/query_store.json.corrupt-7-deadbeef",
            logicalStore = "Saved searches",
            logicalFile = "query_store.json",
            sha256 = "a".repeat(64),
            byteCount = 7L,
        )
        recoveries.value = listOf(recovery)
        runCurrent()

        assertEquals(listOf(recovery), owner.state.value.legacyJsonRecoveries)
    }

    @Test
    fun `configured account state never publishes the stored secret`() = runTest {
        val storedSecret = "stored-secret-must-not-reach-settings-state"
        val accounts = FakeSettingsAccountGateway(
            gelbooru = GelbooruCredentials(userId = "42", apiKey = storedSecret),
        )
        val owner = settingsOwner(accounts = accounts)
        runCurrent()

        assertEquals("42", owner.state.value.accounts.gelbooruUserIdInput)
        assertEquals("Configured", owner.state.value.accounts.gelbooruStatusLabel)
        assertEquals("", owner.state.value.accounts.gelbooruApiKeyInput)
        assertFalse(owner.state.value.toString().contains(storedSecret))
    }

    @Test
    fun `blank unconfigured save keeps missing input visible and never writes`() = runTest {
        val accounts = FakeSettingsAccountGateway()
        val owner = settingsOwner(accounts = accounts)
        runCurrent()

        owner.onAction(SettingsAction.SetGelbooruUserId("42"))
        owner.onAction(SettingsAction.SaveGelbooruCredentials)
        runCurrent()

        assertEquals(0, accounts.gelbooruSaveCount)
        assertEquals("Missing user ID or API key", owner.state.value.accounts.gelbooruStatusLabel)
        assertEquals("", owner.state.value.accounts.gelbooruApiKeyInput)
    }

    @Test
    fun `blank configured save reuses repository key and clears transient input`() = runTest {
        val accounts = FakeSettingsAccountGateway(
            gelbooru = GelbooruCredentials(userId = "42", apiKey = "stored-key"),
        )
        val owner = settingsOwner(accounts = accounts)
        runCurrent()

        owner.onAction(SettingsAction.SetGelbooruUserId("84"))
        owner.onAction(SettingsAction.SaveGelbooruCredentials)
        runCurrent()

        assertEquals(GelbooruCredentials("84", "stored-key"), accounts.savedGelbooru)
        assertEquals("Configured", owner.state.value.accounts.gelbooruStatusLabel)
        assertEquals("", owner.state.value.accounts.gelbooruApiKeyInput)
        assertFalse(owner.state.value.toString().contains("stored-key"))
    }

    @Test
    fun `failed credential mutation preserves recovery presentation after refresh`() = runTest {
        val accounts = FakeSettingsAccountGateway(
            gelbooru = GelbooruCredentials(userId = "42", apiKey = "stored-key"),
        ).apply {
            failGelbooruSaveWithRecovery = CredentialStoreRecoveryState.TemporarilyUnavailable
        }
        val owner = settingsOwner(accounts = accounts)
        runCurrent()

        owner.onAction(SettingsAction.SetGelbooruApiKey("replacement"))
        owner.onAction(SettingsAction.SaveGelbooruCredentials)
        runCurrent()

        assertEquals(1, accounts.gelbooruSaveCount)
        assertEquals(
            "Source credentials are temporarily unavailable — try again",
            owner.state.value.accounts.gelbooruStatusLabel,
        )
        assertEquals("", owner.state.value.accounts.gelbooruApiKeyInput)
        assertFalse(owner.state.value.accounts.mutationsEnabled)
    }

}
