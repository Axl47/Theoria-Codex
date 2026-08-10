package com.theoriacodex.app.codex

import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey

data class CodexCollectionFilters(
    val animatedOnly: Boolean = false,
    val animatedDurationRange: AnimatedDurationRange = AnimatedDurationRange.Full,
    val source: SourceKey? = null,
    val language: CodexLanguageFilter = CodexLanguageFilter.ANY,
    val fullColorOnly: Boolean = false,
) {
    val isActive: Boolean
        get() = animatedOnly || !animatedDurationRange.isFullRange || source != null ||
            language != CodexLanguageFilter.ANY || fullColorOnly
}

enum class CodexLanguageFilter(val tag: String?) {
    ANY(null),
    ENGLISH("english"),
    CHINESE("chinese"),
    JAPANESE("japanese"),
}

fun filterCodexCollectionPosts(
    posts: List<Post>,
    filters: CodexCollectionFilters,
    unknownAnimatedDurationPolicy: UnknownAnimatedDurationPolicy,
    knownDurationMsByPostId: Map<PostId, Long> = emptyMap(),
): List<Post> {
    return filterSearchResults(
        results = posts,
        filters = SearchVisibilityFilters(
            animatedOnly = filters.animatedOnly,
            animatedDurationRange = filters.animatedDurationRange,
        ),
        likedPostIds = emptySet(),
        savedPostIds = emptySet(),
        unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
        knownDurationMsByPostId = knownDurationMsByPostId,
    ).filter { post ->
        (filters.source == null || post.id.source == filters.source) &&
            matchesLanguage(post, filters.language) &&
            (!filters.fullColorOnly || post.hasSupportedTag(FULL_COLOR_TAG))
    }
}

fun supportsCodexLanguageFilter(representedSources: Set<SourceKey>): Boolean {
    return representedSources.any(CODEX_LANGUAGE_SOURCES::contains)
}

fun supportsCodexFullColorFilter(representedSources: Set<SourceKey>): Boolean {
    return representedSources.any(CODEX_FULL_COLOR_SOURCES::contains)
}

private fun matchesLanguage(post: Post, language: CodexLanguageFilter): Boolean {
    val tag = language.tag ?: return true
    if (post.id.source !in CODEX_LANGUAGE_SOURCES) return false
    val target = sourceTagKey(post.id.source, tag)
    return post.taxonomy.any { term ->
        term.facet == SearchFacet.LANGUAGE && sourceTagKey(post.id.source, term.value) == target
    } || post.canonicalTags.any { value -> sourceTagKey(post.id.source, value) == target }
}

private fun Post.hasSupportedTag(tag: String): Boolean {
    if (id.source !in CODEX_FULL_COLOR_SOURCES) return false
    val target = sourceTagKey(id.source, tag)
    return taxonomy.any { term -> sourceTagKey(id.source, term.value) == target } ||
        canonicalTags.any { value -> sourceTagKey(id.source, value) == target }
}

private val CODEX_LANGUAGE_SOURCES = setOf(SourceKey.NHENTAI, SourceKey.HITOMI)
private val CODEX_FULL_COLOR_SOURCES = setOf(SourceKey.NHENTAI, SourceKey.HITOMI)
private const val FULL_COLOR_TAG = "full color"
