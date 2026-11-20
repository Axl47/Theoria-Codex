package com.theoriacodex.app.ui

import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.settings.DefaultSettingsAccountGateway
import com.theoriacodex.app.settings.DefaultSettingsProfileMutations
import com.theoriacodex.app.settings.SettingsOwnerDependencies

/** What the Settings owner is built from, taken from the app container for `SettingsViewModel.factory`. */
internal fun settingsOwnerDependencies(container: TheoriaAppContainer) = SettingsOwnerDependencies(
    settingsRepository = container.data.settingsRepository,
    cacheRepository = container.data.cacheRepository,
    uiRestoreRepository = container.data.uiRestoreRepository,
    likesRepository = container.data.likesRepository,
    codexRepository = container.data.codexRepository,
    statisticsRepository = container.data.statisticsRepository,
    appUsageTracker = container.features.appUsageTracker,
    profileMutations = DefaultSettingsProfileMutations(requireNotNull(container.workflows.profileDeletion)),
    accounts = DefaultSettingsAccountGateway(
        accounts = container.sources.accounts,
        pixivTokens = container.sources.pixivTokens,
        pixivAuthController = container.sources.pixivAuthController,
    ),
    availableSources = container.sources.availableSources,
    recoveredStores = container.data.recoveredStores,
)
