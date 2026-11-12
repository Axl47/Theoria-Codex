package com.theoriacodex.app.comments

import com.theoriacodex.app.creator.browseableCreatorProfile
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey

/** Where opening a commenter's profile leads. */
internal sealed interface AuthorDestination {
    /** The app's creator page, for authors the source can browse there. */
    data class CreatorPage(val profile: CreatorProfile) : AuthorDestination

    /** The author's profile on the site, for those the creator page cannot show. */
    data class Browser(val url: String) : AuthorDestination
}

/** How [author]'s profile opens, or null when there is no author or nothing to open. */
internal fun commentAuthorDestination(author: CreatorProfile?, creatorBrowsingSources: Set<SourceKey>): AuthorDestination? {
    if (author == null) return null
    browseableCreatorProfile(author, creatorBrowsingSources)?.let { return AuthorDestination.CreatorPage(it) }
    return author.profileUrl?.takeIf(String::isNotBlank)?.let(AuthorDestination::Browser)
}
