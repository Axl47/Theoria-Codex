package com.theoriacodex.data.repository

/** User-owned settings must be admitted before persistence, never evicted by storage normalization. */
internal fun AppSettings.requireSettingsCapacity(): AppSettings = apply {
    require(recommendationProfiles.size <= MAX_PERSISTED_RECOMMENDATION_PROFILES) {
        "You can keep up to $MAX_PERSISTED_RECOMMENDATION_PROFILES profiles. Remove one before adding or restoring more."
    }
    require(favoriteTagsByProfile.values.all { it.size <= MAX_PERSISTED_FAVORITE_TAGS_PER_PROFILE }) {
        "A profile can keep up to $MAX_PERSISTED_FAVORITE_TAGS_PER_PROFILE favorite tags. Remove some before adding more."
    }
    require(forYouBlacklistByProfile.values.all { it.size <= MAX_PERSISTED_BLACKLIST_ENTRIES_PER_PROFILE }) {
        "A profile can hide up to $MAX_PERSISTED_BLACKLIST_ENTRIES_PER_PROFILE recommendations. Remove some before adding more."
    }
}
