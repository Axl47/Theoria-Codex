package com.theoriacodex.sources.nhentai

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import java.net.URLEncoder

/** Artist identity shared by API galleries and mirrored gallery metadata. */
internal fun List<PostTaxonomyTerm>.nhentaiCreatorProfiles(): List<CreatorProfile> {
    return asSequence()
        .filter { it.facet == SearchFacet.ARTIST && it.sourceNamespace == NHENTAI_ARTIST_NAMESPACE }
        .mapNotNull { term ->
            val artist = normalizeNhentaiTag(term.value).lowercase().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val slug = artist.replace(NHENTAI_WHITESPACE_REGEX, "-")
            val encodedSlug = URLEncoder.encode(slug, Charsets.UTF_8.name()).replace("+", "%20")
            CreatorProfile(
                source = SourceKey.NHENTAI,
                displayName = term.value.trim(),
                profileId = artist,
                profileUrl = "https://nhentai.net/artist/$encodedSlug/",
                uploadsQuery = SearchTerm(artist, SearchFacet.ARTIST, NHENTAI_ARTIST_NAMESPACE).compileNhentaiTerm(),
            )
        }
        .distinctBy(CreatorProfile::profileId)
        .toList()
}
