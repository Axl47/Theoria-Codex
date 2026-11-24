package com.theoriacodex.app.comments

import com.theoriacodex.app.creator.browseableCreatorProfile
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey

sealed interface AuthorDestination {
    data class CreatorPage(val profile: CreatorProfile) : AuthorDestination

    data class Browser(val url: String) : AuthorDestination
}

internal fun commentAuthorDestination(author: CreatorProfile?, creatorBrowsingSources: Set<SourceKey>): AuthorDestination? {
    if (author == null) return null
    browseableCreatorProfile(author, creatorBrowsingSources)?.let { return AuthorDestination.CreatorPage(it) }
    return author.profileUrl?.takeIf(String::isNotBlank)?.let(AuthorDestination::Browser)
}
