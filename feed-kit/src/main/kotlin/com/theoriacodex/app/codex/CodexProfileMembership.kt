package com.theoriacodex.app.codex

fun codexBelongsToProfile(codexId: String, profileId: String): Boolean {
    return com.theoriacodex.data.repository.ProfileLibraryIds.belongsTo(codexId, profileId)
}
