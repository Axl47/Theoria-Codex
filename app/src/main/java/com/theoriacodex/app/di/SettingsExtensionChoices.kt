package com.theoriacodex.app.di

import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import kotlinx.coroutines.flow.first

/** Keeps which extensions and languages are on in the app's settings store. */
internal class SettingsExtensionChoices(private val settings: SettingsRepository) : ExtensionChoiceStore {
    override suspend fun choices(): ExtensionChoices = settings.observeSettings().first().extensions

    override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) {
        settings.updateSettings { it.copy(extensions = transform(it.extensions)) }
    }
}
