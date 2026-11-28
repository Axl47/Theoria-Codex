package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerPrefetchRequest
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

internal object ViewerOwnerTestData {
    fun request(key: ViewerMediaKey, distance: Int = 2) =
        ViewerPrefetchRequest(key, distance, decode = false)

    fun queue(vararg keys: ViewerMediaKey, distance: Int = 2) =
        ViewerAction.QueuePrefetch(keys.map { key -> request(key, distance) })

    fun session(id: String, posts: List<Post>, startIndex: Int = 0) = ViewerSession(
        posts = posts,
        context = ViewerLaunchContext(queryHash = "query:$id", startIndex = startIndex,
            streamSource = ViewerStreamSource.SEARCH, scrollOffsetHint = 0),
        sessionId = id,
    )

    fun post(
        id: String,
        source: SourceKey = SourceKey.PIXIV,
        media: List<ImageRef> = listOf(image("$id.jpg")),
        title: String? = null,
    ): Post {
        val preview = media.firstOrNull() ?: image("$id-preview.jpg")
        return Post(
            id = PostId(source, id),
            preview = preview,
            full = media.firstOrNull(),
            media = media,
            pageUrl = "https://example.test/$id",
            width = 100,
            height = 100,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = title,
        )
    }

    fun image(path: String) = ImageRef(url = "https://example.test/$path", localPath = null, mime = "image/jpeg")

    fun gif(path: String) = ImageRef(url = "https://example.test/$path", localPath = null, mime = "image/gif")

    fun video(path: String) = ImageRef(url = "https://example.test/$path", localPath = null, mime = "video/mp4")
}
