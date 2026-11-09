package com.theoriacodex.app.settings

import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.sourceauth.CredentialStoreRecoveryState
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.storage.CorruptionRecovery
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.GelbooruCredentials
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test
    fun `library statistics are read only while a statistics surface subscribes`() = runTest {
        val library = InMemoryCodexRepository()
        var librarySubscriptions = 0
        val counted = object : CodexRepository by library {
            override fun observeLibraryPosts(codexIds: Set<String>) =
                library.observeLibraryPosts(codexIds).onStart { librarySubscriptions++ }
        }
        val codex = library.ensureCodex(profileScopedCodexId("profile-main", "saved"), "Saved here")
        val owner = settingsOwner(codexRepository = counted)
        runCurrent()
        library.addItem(codex.codexId, ownerPost("first"))
        runCurrent()
        assertEquals(0, librarySubscriptions)

        assertEquals(1L, owner.statistics.first { it.savedPostCount == 1L }.savedPostCount)
        assertEquals(1, librarySubscriptions)
    }

    @Test
    fun `opening Sites from elsewhere selects the page and navigates to Settings`() = runTest {
        val owner = settingsOwner()
        runCurrent()

        owner.onAction(SettingsAction.OpenCustomSites)
        runCurrent()

        assertEquals(SettingsPage.SITES, owner.state.value.page)
        assertEquals(SettingsEffect.NavigateToSettings, owner.effects.first())
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

        assertTrue(owner.state.value.accounts.gelbooruConfigured)
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

        assertFalse(owner.state.value.accounts.gelbooruConfigured)
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
        assertFalse(owner.state.value.accounts.gelbooruConfigured)
        assertFalse(owner.state.value.accounts.rule34XxxConfigured)
        assertFalse(owner.state.value.accounts.mutationsEnabled)
    }

    @Test
    fun `saved API key accounts remain configured until their own disconnect`() = runTest {
        val owner = settingsOwner()
        runCurrent()
        owner.onAction(SettingsAction.SetGelbooruUserId("42"))
        owner.onAction(SettingsAction.SetGelbooruApiKey("gelbooru-key"))
        owner.onAction(SettingsAction.SaveGelbooruCredentials)
        runCurrent()
        assertTrue(owner.state.value.accounts.gelbooruConfigured)
        assertFalse(owner.state.value.accounts.rule34XxxConfigured)

        owner.onAction(SettingsAction.SetRule34XxxUserId("84"))
        owner.onAction(SettingsAction.SaveRule34XxxCredentials)
        runCurrent()
        assertFalse(owner.state.value.accounts.rule34XxxConfigured)
        assertEquals("Missing user ID or API key", owner.state.value.accounts.rule34XxxStatusLabel)
        owner.onAction(SettingsAction.SetRule34XxxApiKey("rule34-key"))
        owner.onAction(SettingsAction.SaveRule34XxxCredentials)
        runCurrent()
        assertTrue(owner.state.value.accounts.rule34XxxConfigured)
        assertEquals("", owner.state.value.accounts.rule34XxxApiKeyInput)

        owner.onAction(SettingsAction.ClearGelbooruCredentials)
        runCurrent()
        assertFalse(owner.state.value.accounts.gelbooruConfigured)
        assertTrue(owner.state.value.accounts.rule34XxxConfigured)
        assertEquals("", owner.state.value.accounts.gelbooruUserIdInput)
        owner.onAction(SettingsAction.ClearRule34XxxCredentials)
        runCurrent()
        assertFalse(owner.state.value.accounts.rule34XxxConfigured)
        assertEquals("", owner.state.value.accounts.rule34XxxUserIdInput)
    }

    private fun ownerPost(id: String) = Post(
        id = PostId(SourceKey.PIXIV, id),
        preview = ImageRef(url = null, localPath = null, mime = null),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = listOf("saved"),
        rawTags = listOf("saved"),
        authorName = null,
        createdAtEpochMs = null,
    )
}
