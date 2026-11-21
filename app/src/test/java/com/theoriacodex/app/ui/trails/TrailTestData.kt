package com.theoriacodex.app.ui.trails

import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

internal object TrailTestData {
    fun post(id: String, source: SourceKey = SourceKey.PIXIV) = Post(
        id = PostId(source, id),
        preview = ImageRef(url = "https://example.test/$id.jpg", localPath = null, mime = "image/jpeg"),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )

    /** Related posts for [supported] sources only; [load] answers every seed. */
    fun related(supported: Set<SourceKey> = emptySet(), load: (PostId) -> List<Post> = { emptyList() }) = object : RelatedPostsLoading {
        override fun supports(source: SourceKey) = source in supported
        override suspend fun load(seed: PostId) = load(seed)
    }
}
