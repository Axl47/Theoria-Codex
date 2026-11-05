package com.theoriacodex.data.repository

/** Retired identities remain blocked until process exit or until a restore brings the profile back. */
class ProfileLibraryWriteGuard {
    private val retired = mutableSetOf<String>()

    @Synchronized fun retire(profileId: String) { retired += profileId }
    /** Only for a profile a restore has committed: it exists again, so its writes can no longer orphan rows. */
    @Synchronized fun reinstate(profileId: String) { retired -= profileId }
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
