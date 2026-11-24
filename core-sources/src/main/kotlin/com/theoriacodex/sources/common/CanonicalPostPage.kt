package com.theoriacodex.sources.common

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.model.Post

internal fun canonicalPostPage(items: List<Post>, nextPageToken: String?): Page<Post> =
    Page(items = items.distinctBy(Post::id), nextPageToken = nextPageToken)
