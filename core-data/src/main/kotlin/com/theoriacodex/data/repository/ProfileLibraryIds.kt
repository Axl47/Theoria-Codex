package com.theoriacodex.data.repository

/** Shared identity policy used by library navigation and portable backup remapping. */
object ProfileLibraryIds {
    const val CODEX_PREFIX = "profile_codex"
    const val LIKES_PREFIX = "system_likes_codex"

    fun likes(profileId: String): String =
        if (profileId == "profile-main") LIKES_PREFIX else "${LIKES_PREFIX}_$profileId"

    fun codex(profileId: String, uniqueId: String): String = "${CODEX_PREFIX}_${profileId}_$uniqueId"

    fun belongsTo(codexId: String, profileId: String): Boolean = when {
        codexId.startsWith(LIKES_PREFIX) -> codexId == likes(profileId)
        codexId.startsWith("${CODEX_PREFIX}_") -> codexId.startsWith("${CODEX_PREFIX}_${profileId}_")
        else -> profileId == "profile-main"
    }
}
