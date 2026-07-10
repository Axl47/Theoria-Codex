package com.theoriacodex.app.testing

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SourceKey

internal fun testPost(
    source: SourceKey = SourceKey.PIXIV,
    sourcePostId: String = "post-1",
    preview: ImageRef = ImageRef(
        url = "https://example.com/$sourcePostId.jpg",
        localPath = null,
        mime = "image/jpeg",
    ),
    full: ImageRef? = ImageRef(
        url = "https://example.com/full/$sourcePostId.jpg",
        localPath = null,
        mime = "image/jpeg",
    ),
    media: List<ImageRef> = emptyList(),
    pageUrl: String? = "https://example.com/post/$sourcePostId",
    width: Int? = 100,
    height: Int? = 100,
    canonicalTags: List<String> = listOf("landscape"),
    rawTags: List<String> = canonicalTags,
    authorName: String? = "artist",
    createdAtEpochMs: Long? = 1L,
    title: String? = null,
    creatorProfile: CreatorProfile? = null,
    durationMs: Long? = null,
    mediaCount: Int? = null,
    taxonomy: List<PostTaxonomyTerm> = canonicalTags.map(::PostTaxonomyTerm),
    creatorProfiles: List<CreatorProfile> = listOfNotNull(creatorProfile),
): Post {
    return Post(
        id = PostId(source = source, sourcePostId = sourcePostId),
        preview = preview,
        full = full,
        media = media,
        pageUrl = pageUrl,
        width = width,
        height = height,
        canonicalTags = canonicalTags,
        rawTags = rawTags,
        authorName = authorName,
        createdAtEpochMs = createdAtEpochMs,
        title = title,
        creatorProfile = creatorProfile,
        durationMs = durationMs,
        mediaCount = mediaCount,
        taxonomy = taxonomy,
        creatorProfiles = creatorProfiles,
    )
}
