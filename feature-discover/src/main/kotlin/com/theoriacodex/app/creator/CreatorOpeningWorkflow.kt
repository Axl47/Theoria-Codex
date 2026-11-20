package com.theoriacodex.app.creator

import com.theoriacodex.app.source.ExternalCreatorDeepLink
import com.theoriacodex.app.source.creatorBrowsingSources
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.gelbooru.fetchGelbooruProfileOwner
import com.theoriacodex.sources.http.SourceHttpClient
import kotlinx.coroutines.withTimeoutOrNull

data class CreatorOpeningOutcome(val creator: CreatorProfile?, val resolvedPost: Post? = null)

/** Resolves creator identity only; the shell owns navigation and committing a hydrated Post. */
class CreatorOpeningWorkflow(
    private val registry: SourceAdapterRegistry,
    private val http: SourceHttpClient,
) {
    suspend fun fromPost(post: Post, availableSources: Set<SourceKey>): CreatorOpeningOutcome {
        val sources = registry.creatorBrowsingSources().intersect(availableSources)
        fun profile(post: Post): CreatorProfile? = post.creatorProfiles.firstNotNullOfOrNull {
            browseableCreatorProfile(it, sources)
        } ?: browseableCreatorProfile(post.creatorProfile, sources)
        profile(post)?.let { return CreatorOpeningOutcome(it) }
        if (post.id.source !in sources) return CreatorOpeningOutcome(null)
        val resolved = withTimeoutOrNull(15_000) {
            runCatchingPreservingCancellation { registry.adapterFor(post.id.source)?.resolvePost(post.id) }.getOrNull()
        }
        return CreatorOpeningOutcome(resolved?.let(::profile), resolved)
    }

    suspend fun fromDeepLink(link: ExternalCreatorDeepLink, availableSources: Set<SourceKey>): CreatorProfile? {
        val sources = registry.creatorBrowsingSources().intersect(availableSources)
        if (link.source !in sources) return null
        val profile = when (link.source) {
            SourceKey.PIXIV -> CreatorProfile(link.source, "User ${link.creatorId}", link.creatorId, link.profileUrl, link.creatorId)
            SourceKey.HITOMI -> CreatorProfile(link.source, link.creatorId, link.creatorId, link.profileUrl, "artist:${link.creatorId}")
            SourceKey.GELBOORU -> {
                val lookup = withTimeoutOrNull(15_000) {
                    runCatchingPreservingCancellation {
                        fetchGelbooruProfileOwner(http, link.creatorId, link.source.requestHeaders())
                    }.takeIf { it.isSuccess }
                } ?: return null
                val owner = lookup.getOrNull()
                CreatorProfile(link.source, owner ?: link.creatorId, link.creatorId, link.profileUrl, owner?.let { "user:$it" })
            }
            else -> return null
        }
        return browseableCreatorProfile(profile, sources)
    }
}
