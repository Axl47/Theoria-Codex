package com.theoriacodex.domain.model

/** A post list an account page can show; each source offers the ones it can list publicly. */
enum class AccountFeed {
    /** Posts the account uploaded; the same list as the creator's uploads search. */
    UPLOADS,

    /** Posts the account favorited or bookmarked publicly, newest favorite first where the source orders them. */
    FAVORITES,
}

/**
 * What a source says publicly about an account, for the account page's header. Every field is optional
 * because no source has them all. A [favoriteCount] of 0 also hides the Favorites tab, since some sites
 * (Danbooru) report an empty list for accounts whose favorites are private.
 */
data class AccountDetails(
    /** Absolute URL; requests to it carry the source's media headers. */
    val avatarUrl: String? = null,
    /** Plain text the account wrote about itself. */
    val bio: String? = null,
    val joinedAtEpochMs: Long? = null,
    val uploadCount: Int? = null,
    val favoriteCount: Int? = null,
    val followerCount: Int? = null,
)
