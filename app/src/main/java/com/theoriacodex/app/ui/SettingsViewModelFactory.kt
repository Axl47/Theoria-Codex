package com.theoriacodex.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.settings.DefaultSettingsAccountGateway
import com.theoriacodex.app.settings.DefaultSettingsProfileMutations
import com.theoriacodex.app.settings.SettingsOwnerDependencies
import com.theoriacodex.app.settings.SettingsViewModel

internal fun settingsViewModelFactory(container: TheoriaAppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        SettingsViewModel(
            dependencies = SettingsOwnerDependencies(
                settingsRepository = container.data.settingsRepository,
                cacheRepository = container.data.cacheRepository,
                uiRestoreRepository = container.data.uiRestoreRepository,
                likesRepository = container.data.likesRepository,
                codexRepository = container.data.codexRepository,
                statisticsRepository = container.data.statisticsRepository,
                appUsageTracker = container.features.appUsageTracker,
                profileMutations = DefaultSettingsProfileMutations(container.workflows.profileDeletion),
                accounts = DefaultSettingsAccountGateway(
                    accounts = container.sources.accounts,
                    pixivTokens = container.sources.pixivTokens,
                    pixivAuthController = container.sources.pixivAuthController,
                ),
                availableSources = container.sources.availableSources,
                recoveredStores = container.data.recoveredStores,
            )
        )
    }
}
