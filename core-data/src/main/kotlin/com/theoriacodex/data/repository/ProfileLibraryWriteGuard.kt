package com.theoriacodex.data.repository

/** Retired identities remain blocked until process exit, including after deletion has committed. */
class ProfileLibraryWriteGuard {
    private val retired = mutableSetOf<String>()

    @Synchronized fun retire(profileId: String) { retired += profileId }
    @Synchronized fun isRetired(profileId: String): Boolean = profileId in retired

    @Synchronized fun requireProfileWritable(profileId: String) {
        check(profileId !in retired) { "This profile is no longer available for changes" }
    }

    @Synchronized fun requireCodexWritable(codexId: String) {
        check(retired.none { ProfileLibraryIds.belongsTo(codexId, it) }) {
            "This profile is no longer available for changes"
        }
    }
}
