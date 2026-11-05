package com.theoriacodex.data.repository

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
