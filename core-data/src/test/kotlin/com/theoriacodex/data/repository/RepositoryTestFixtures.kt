package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

internal fun repositoryTestPost(
    id: String,
    localPath: String? = null,
    source: SourceKey = SourceKey.PIXIV,
    title: String? = null,
): Post {
    val creator = CreatorProfile(
        source = source,
        displayName = "artist",
        profileId = "profile-$id",
        profileUrl = "https://example.com/creator/$id",
        uploadsQuery = "uploads-$id",
    )
    return Post(
        id = PostId(source = source, sourcePostId = id),
        preview = ImageRef(
            url = "https://example.com/$id.jpg",
            localPath = localPath,
            mime = "image/jpeg",
        ),
        full = ImageRef(
            url = "https://example.com/full/$id.jpg",
            localPath = null,
            mime = "image/jpeg",
        ),
        pageUrl = "https://example.com/post/$id",
        width = 100,
        height = 100,
        canonicalTags = listOf("landscape"),
        rawTags = listOf("landscape"),
        authorName = "artist",
        createdAtEpochMs = 1L,
        title = title,
        creatorProfile = creator,
    )
}
