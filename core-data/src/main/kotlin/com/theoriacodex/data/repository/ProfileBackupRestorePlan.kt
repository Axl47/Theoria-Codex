package com.theoriacodex.data.repository

/**
 * Only for a journal written before restores kept the backup's own IDs: its database commit may already
 * have used these IDs, so finishing it any other way could duplicate rows or overflow capacity at startup.
 */
internal fun ProfileBackup.forLegacyRestore(operationId: String): ProfileBackup {
    val profileMap = settings.recommendationProfiles.mapIndexed { index, profile ->
        profile.profileId to "restored-$operationId-$index"
    }.toMap()
    val codexMap = library.codices.mapIndexed { index, codex ->
        val owner = profileMap.keys.single { ProfileLibraryIds.belongsTo(codex.codexId, it) }
        val restoredId = if (codex.codexId == ProfileLibraryIds.likes(owner)) {
            ProfileLibraryIds.likes(profileMap.getValue(owner))
        } else {
            ProfileLibraryIds.codex(profileMap.getValue(owner), "$operationId-$index")
        }
        codex.codexId to restoredId
    }.toMap()
    return copy(
        settings = settings.copy(
            followedCreators = settings.followedCreators.mapIndexed { index, follow ->
                follow.copy(membershipId = "restored-$operationId-$index")
            },
            recommendationProfiles = settings.recommendationProfiles.map {
                it.copy(profileId = profileMap.getValue(it.profileId), name = "${it.name} (restored)")
            },
            activeProfileId = profileMap.getValue(settings.activeProfileId),
            forYouBlacklistByProfile = settings.forYouBlacklistByProfile.mapKeys { profileMap.getValue(it.key) },
            favoriteTagsByProfile = settings.favoriteTagsByProfile.mapKeys { profileMap.getValue(it.key) },
        ),
        library = library.copy(
            codices = library.codices.map { it.copy(codexId = codexMap.getValue(it.codexId)) },
            items = library.items.map { it.copy(codexId = codexMap.getValue(it.codexId)) },
            likes = library.likes.map { it.copy(profileId = profileMap.getValue(it.profileId)) },
        ),
        savedSearches = savedSearches.mapIndexed { index, entry ->
            entry.copy(id = "restored-$operationId-$index")
        },
    )
}

/** Adds what the backup has and this device lacks; anything already here keeps its current values. */
internal fun AppSettings.mergeRestoredSettings(imported: AppSettings, applyPreferences: Boolean): AppSettings {
    val existingProfileIds = recommendationProfiles.mapTo(mutableSetOf()) { it.profileId }
    val follows = (followedCreators + imported.followedCreators).distinctBy { it.creator.followKey() }
    require(follows.size <= MAX_FOLLOWED_CREATORS) { "Restore would exceed the followed-creator limit" }
    val preferences = if (applyPreferences) imported else this
    return copy(
        runtime = preferences.runtime,
        cache = preferences.cache,
        contentFilters = preferences.contentFilters,
        viewer = preferences.viewer,
        recommendationProfiles = recommendationProfiles + imported.recommendationProfiles.filterNot {
            it.profileId in existingProfileIds
        },
        favoriteTagsByProfile = favoriteTagsByProfile.unionByProfile(imported.favoriteTagsByProfile),
        forYouBlacklistByProfile = forYouBlacklistByProfile.unionByProfile(imported.forYouBlacklistByProfile),
        followedCreators = follows,
    ).requireSettingsCapacity()
}

private fun <T> Map<String, List<T>>.unionByProfile(imported: Map<String, List<T>>): Map<String, List<T>> =
    (keys + imported.keys).associateWith { profileId ->
        (this[profileId].orEmpty() + imported[profileId].orEmpty()).distinct()
    }
