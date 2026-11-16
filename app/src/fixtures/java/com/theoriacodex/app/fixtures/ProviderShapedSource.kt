package com.theoriacodex.app.fixtures

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.danbooru.DanbooruSite
import com.theoriacodex.sources.danbooru.DanbooruSourceAdapter
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A provider served from memory to the real Danbooru adapter, so a journey parses provider-shaped JSON instead of
 * receiving ready-made posts. It answers `/posts.json` newest first from [posts], honouring `limit` and both paging
 * forms (`page=N` and the `b<id>` cursor); `tags` is ignored. It answers `/posts/<id>.json` with that post, or 404.
 * Every other request goes to [fallback]. The JSON is generated from fixture posts, so no provider data is recorded
 * or stored.
 */
class ProviderShapedSource(posts: List<Post>, private val fallback: SourceHttpClient) : SourceHttpClient {
    private val newestFirst = posts.sortedByDescending { it.id.sourcePostId.toLong() }

    /** The `page` value of every `/posts.json` request, in order; each one was answered. */
    val pageRequests = CopyOnWriteArrayList<String>()

    /** The id of every single-post request, in order; each one was answered. */
    val postRequests = CopyOnWriteArrayList<String>()

    fun adapter(source: SourceKey): SourceAdapter =
        DanbooruSourceAdapter(DanbooruSite(source = source, label = "Journey ${source.id}", baseUrl = BASE_URL), this)

    override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
        if (url != "$BASE_URL/posts.json") {
            val postId = SINGLE_POST.matchEntire(url)?.groupValues?.get(1) ?: return fallback.get(url, query, headers)
            postRequests += postId
            val post = newestFirst.firstOrNull { it.id.sourcePostId == postId } ?: return SourceHttpResponse(404, "{}")
            return SourceHttpResponse(200, post.toDanbooruJson().toString())
        }
        val page = query["page"] ?: "1"
        pageRequests += page
        val limit = query["limit"]?.toInt() ?: DEFAULT_LIMIT
        val selected = if (page.startsWith("b")) {
            val beforeId = page.drop(1).toLong()
            newestFirst.filter { it.id.sourcePostId.toLong() < beforeId }.take(limit)
        } else {
            newestFirst.drop((page.toInt() - 1) * limit).take(limit)
        }
        return SourceHttpResponse(200, JsonArray().apply { selected.forEach { add(it.toDanbooruJson()) } }.toString())
    }

    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>) =
        fallback.postForm(url, form, headers)

    private companion object {
        const val BASE_URL = "https://provider.journey.invalid"
        const val DEFAULT_LIMIT = 20
        val SINGLE_POST = Regex(Regex.escape(BASE_URL) + """/posts/(\d+)\.json""")
    }
}

/**
 * [count] posts the Danbooru adapter can carry, newest first: numeric ids from [count] down to 1, so paging by
 * id cursor works, and [media] (a local file) for every image.
 */
fun providerShapedPosts(source: SourceKey, count: Int, media: ImageRef): List<Post> = (count downTo 1).map { id ->
    Post(
        id = PostId(source, id.toString()), preview = media, full = media, pageUrl = null,
        width = 320, height = 320, canonicalTags = listOf("landscape", "journey_post_$id"),
        rawTags = emptyList(), authorName = "journey artist", createdAtEpochMs = 1_000L + id,
        creatorProfile = CreatorProfile(source, "journey artist", "journey_artist", uploadsQuery = "journey_artist"),
    )
}

private fun Post.toDanbooruJson() = JsonObject().apply {
    val location = (full ?: preview).let { it.localPath ?: it.url }
    addProperty("id", id.sourcePostId.toLong())
    addProperty("file_url", location)
    addProperty("preview_file_url", preview.localPath ?: preview.url)
    addProperty("file_ext", location?.substringAfterLast('.'))
    addProperty("image_width", width)
    addProperty("image_height", height)
    addProperty("tag_string", canonicalTags.joinToString(" "))
    addProperty("tag_string_artist", creatorProfile?.profileId)
    addProperty("created_at", createdAtEpochMs?.let { Instant.ofEpochMilli(it).toString() })
    addProperty("source", originUrl)
}
