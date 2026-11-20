package com.theoriacodex.app.settings

import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsMutationFailureTest {
    @Test fun `failed preference write reports a recoverable outcome and retains durable settings`() = runTest {
        val stored = InMemorySettingsRepository()
        val original = stored.observeSettings().first()
        val failing = object : SettingsRepository by stored {
            override suspend fun setEnabledSources(enabledSources: Set<SourceKey>) = error("disk unavailable")
        }
        val owner = settingsOwner(settingsRepository = failing)
        runCurrent()
        owner.onAction(SettingsAction.SetEnabledSources(emptySet()))
        runCurrent()
        assertEquals(SettingsEffect.ShowMessage("Could not save Settings. Please try again."), owner.effects.first())
        assertEquals(original, stored.observeSettings().first())
    }
}
