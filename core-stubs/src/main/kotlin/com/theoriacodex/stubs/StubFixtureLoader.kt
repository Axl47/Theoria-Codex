package com.theoriacodex.stubs

import com.google.gson.Gson
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

class StubFixtureLoader(
    private val gson: Gson = Gson(),
    private val rootPath: String = "stubs",
    private val classLoader: ClassLoader = StubFixtureLoader::class.java.classLoader,
) {
    fun loadSearchPage(sourceKey: SourceKey, pageToken: String?): SearchPageFixture {
        val pageIndex = when (pageToken) {
            null -> 1
            "page_2" -> 2
            else -> return SearchPageFixture(nextPageToken = null, items = emptyList())
        }
        val path = "$rootPath/${sourceFolder(sourceKey)}/search_page_${pageIndex}.json"
        return readJson(path, SearchPageFixture::class.java)
    }

    fun loadTrending(sourceKey: SourceKey): TrendingTagsFixture {
        val path = "$rootPath/${sourceFolder(sourceKey)}/trending_tags.json"
        return readJson(path, TrendingTagsFixture::class.java)
    }

    fun loadFailureScenario(): FailureScenarioFixture {
        return readJson("$rootPath/scenarios/failures.json", FailureScenarioFixture::class.java)
    }

    fun loadEmptyScenario(): EmptyScenarioFixture {
        return readJson("$rootPath/scenarios/empties.json", EmptyScenarioFixture::class.java)
    }

    private fun <T> readJson(path: String, clazz: Class<T>): T {
        val stream = classLoader.getResourceAsStream(path)
            ?: error("Missing stub fixture: $path")
        return stream.use {
            gson.fromJson(it.reader(), clazz)
        }
    }

    private fun sourceFolder(sourceKey: SourceKey): String {
        return when (sourceKey) {
            SourceKey.PIXIV -> "pixiv"
            SourceKey.GELBOORU -> "gelbooru"
            SourceKey.AIBOORU -> "aibooru"
            SourceKey.NHENTAI -> "nhentai"
            SourceKey.HITOMI -> "hitomi"
            SourceKey.IWARA -> "iwara"
            SourceKey.RULE34XXX -> "rule34xxx"
            SourceKey.RULE34PAHEAL -> "rule34paheal"
            SourceKey.RULE34VIDEO -> "rule34video"
            SourceKey.RULE34GEN -> "rule34gen"
        }
    }
}

data class SearchPageFixture(
    val nextPageToken: String?,
    val items: List<SearchPostFixture>,
)

data class SearchPostFixture(
    val sourcePostId: String,
    val title: String? = null,
    val previewUrl: String?,
    val fullUrl: String?,
    val pageUrl: String?,
    val width: Int?,
    val height: Int?,
    val canonicalTags: List<String>,
    val rawTags: List<String>,
    val authorName: String?,
    val createdAtEpochMs: Long?,
    val durationMs: Long? = null,
) {
    fun toDomain(sourceKey: SourceKey): Post {
        return Post(
            id = PostId(source = sourceKey, sourcePostId = sourcePostId),
            preview = ImageRef(url = previewUrl, localPath = null, mime = null),
            full = fullUrl?.let { ImageRef(url = it, localPath = null, mime = null) },
            pageUrl = pageUrl,
            width = width,
            height = height,
            canonicalTags = canonicalTags,
            rawTags = rawTags,
            authorName = authorName,
            createdAtEpochMs = createdAtEpochMs,
            title = title,
            durationMs = durationMs,
        )
    }
}

data class TrendingTagsFixture(
    val items: List<TrendingTagFixture>,
)

data class TrendingTagFixture(
    val text: String,
    val type: String?,
    val count: Int?,
) {
    fun toDomain(): TagSuggestion {
        return TagSuggestion(text = text, type = type, count = count)
    }
}

data class FailureScenarioFixture(
    val sources: Map<String, SourceFailureConfig>,
)

data class EmptyScenarioFixture(
    val sources: Map<String, Boolean>,
)
