package com.theoriacodex.sources.nhentai

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.sources.common.sourceNetworkFailure
import com.theoriacodex.sources.http.RequestPacer
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.IOException
import com.theoriacodex.domain.encoding.percentEncode

internal class NhentaiMirror(
    private val httpClient: SourceHttpClient,
    private val pacer: RequestPacer,
) {
    suspend fun search(
        compiledQuery: String,
        sort: SortMode,
        pageIndex: Int,
        useAllEndpoint: Boolean,
    ): NhentaiSearchPage {
        val pathAndQuery = if (useAllEndpoint) {
            "/?page=$pageIndex"
        } else {
            val params = linkedMapOf(
                "q" to compiledQuery,
                "page" to pageIndex.toString(),
            )
            mapMirrorSortParam(sort)?.let { params["sort"] = it }
            "/search/?" + params.entries.joinToString("&") { (key, value) ->
                "${key.percentEncode()}=${value.percentEncode()}"
            }
        }
        return NhentaiMirrorParser.parseSearchPage(requestText(pathAndQuery))
    }

    suspend fun resolvePost(galleryId: String): Post? {
        val post = NhentaiMirrorParser.parseReaderPage(galleryId, requestText("/g/$galleryId/1/")) ?: return null
        val metadata = galleryMetadata(galleryId) ?: return post
        val creators = metadata.taxonomy.nhentaiCreatorProfiles()
        return post.copy(
            creatorProfile = creators.firstOrNull(),
            creatorProfiles = creators,
            canonicalTags = metadata.canonicalTags,
            rawTags = metadata.canonicalTags,
            authorName = metadata.authorName,
            taxonomy = metadata.taxonomy,
        )
    }

    private suspend fun galleryMetadata(galleryId: String): NhentaiMirrorMetadata? {
        METADATA_MIRROR_BASES.forEach { baseUrl ->
            val response = requestMetadata("$baseUrl/g/$galleryId/")
            if (response != null && response.statusCode in 200..299) {
                NhentaiMirrorParser.parseGalleryMetadata(response.body)?.let { metadata ->
                    if (metadata.taxonomy.isNotEmpty()) return metadata
                }
            }
        }
        return null
    }

    private suspend fun requestMetadata(url: String): SourceHttpResponse? {
        pacer.awaitTurn()
        return runCatchingPreservingCancellation {
            httpClient.get(
                url = url,
                query = emptyMap(),
                headers = METADATA_MIRROR_HEADERS,
            )
        }.getOrNull()
    }

    private suspend fun requestText(pathAndQuery: String): String {
        val mirrorUrl = JINA_MIRROR_BASE + NHENTAI_WEB_BASE + pathAndQuery.removePrefix("/")
        val response = try {
            httpClient.get(
                url = mirrorUrl,
                query = emptyMap(),
                headers = JINA_REQUEST_HEADERS,
            )
        } catch (error: IOException) {
            sourceNetworkFailure("NHentai mirror", error)
        }
        return response.ensureNhentaiOk("NHentai mirror").body
    }

    private companion object {
        const val JINA_MIRROR_BASE = "https://r.jina.ai/http://"
        const val NHENTAI_WEB_BASE = "nhentai.net/"
        val METADATA_MIRROR_BASES = listOf(
            "https://nhentai.to",
            "https://nhentai.website",
        )
        val JINA_REQUEST_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Accept" to "text/plain, */*",
        )
        val METADATA_MIRROR_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
    }
}
