package com.theoriacodex.sources.testing

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse

fun commentTestPost(source: SourceKey, sourcePostId: String) = Post(
    id = PostId(source, sourcePostId),
    preview = ImageRef(url = null, localPath = null, mime = null),
    full = null,
    pageUrl = null,
    width = null,
    height = null,
    canonicalTags = emptyList(),
    rawTags = emptyList(),
    authorName = null,
    createdAtEpochMs = null,
)

class RoutedHttpClient(private val routes: Map<String, SourceHttpResponse>) : SourceHttpClient {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()

    override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse {
        requests += url to query
        return routes[url] ?: error("Unexpected GET $url $query")
    }

    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
        error("Unexpected POST $url")
}
