package com.theoriacodex.domain.model

enum class AccountFeed {
    UPLOADS,

    FAVORITES,
}

data class AccountDetails(
    val avatarUrl: String? = null,
    val bio: String? = null,
    val joinedAtEpochMs: Long? = null,
    val uploadCount: Int? = null,
    val favoriteCount: Int? = null,
    val followerCount: Int? = null,
)
