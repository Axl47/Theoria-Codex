package com.theoriacodex.app.update

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.data.repository.checkDocumentDepth
import com.theoriacodex.data.repository.decodeDocumentUtf8
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.DefaultSourceHttpClient

class GitHubReleaseFeedClient(
    private val owner: String,
    private val repo: String,
    private val channel: String,
    private val assetName: String,
    private val httpClient: SourceHttpClient = DefaultSourceHttpClient(),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : UpdateFeedClient {
    private val mutex = Mutex()
    private var cachedHistory: List<RemoteUpdate>? = null
    private var cachedAt = 0L

    override suspend fun latestMainPrerelease(): Result<RemoteUpdate?> = history().map { it.firstOrNull() }

    override suspend fun mainPrereleaseHistory(limit: Int): Result<List<RemoteUpdate>> =
        history().map { it.take(limit.coerceAtLeast(1)) }

    private suspend fun history(): Result<List<RemoteUpdate>> = withContext(Dispatchers.IO) {
        runCatchingPreservingCancellation {
            mutex.withLock {
                cachedHistory?.takeIf { nowMs() - cachedAt in 0 until 300_000L } ?: fetchHistory().also {
                    cachedHistory = it
                    cachedAt = nowMs()
                }
            }
        }
    }

    private suspend fun fetchHistory(): List<RemoteUpdate> {
        val response = withTimeoutOrNull(15_000L) {
            httpClient.getBytes(
                url = "https://api.github.com/repos/$owner/$repo/releases",
                query = mapOf("per_page" to "50"),
                headers = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "TheoriaCodexUpdater"),
                maxBodyBytes = 4 * 1024 * 1024,
            )
        } ?: throw IOException("GitHub update check timed out")
        if (response.statusCode !in 200..299) throw IOException("GitHub update check failed (${response.statusCode})")
        val body = decodeDocumentUtf8(response.body)
        checkDocumentDepth(body)
        return parseMainPrereleaseHistory(body, channel, assetName)
    }

    companion object {
        internal fun parseMainPrereleaseHistory(
            jsonBody: String,
            channel: String,
            assetName: String,
        ): List<RemoteUpdate> {
            val root = runCatching { JsonParser.parseString(jsonBody) }.getOrNull() ?: return emptyList()
            val releases = root.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

            return releases.mapNotNull { element ->
                val release = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val draft = release.get("draft")?.asBoolean ?: true
                val prerelease = release.get("prerelease")?.asBoolean ?: false
                if (draft || !prerelease) return@mapNotNull null

                val tagName = release.get("tag_name")?.asString?.trim().orEmpty()
                val targetCommitish = release.get("target_commitish")?.asString?.trim().orEmpty()
                val parsedTag = MainReleaseTagParser.parse(
                    channel = channel,
                    tagName = tagName,
                    fallbackCommitSha = targetCommitish,
                )
                    ?: return@mapNotNull null

                val publishedAt = release.get("published_at")?.asString?.trim().orEmpty()
                val publishedEpoch = runCatching { Instant.parse(publishedAt).toEpochMilli() }.getOrElse { 0L }

                val assets = release.getAsJsonArray("assets") ?: JsonArray()
                val asset = assets.firstAssetByName(assetName) ?: return@mapNotNull null
                val downloadUrl = asset.get("browser_download_url")?.asString?.trim().orEmpty()
                if (downloadUrl.isBlank()) return@mapNotNull null

                val releaseId = release.get("id")?.asLong ?: return@mapNotNull null
                val releaseName = release.get("name")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val changelogMarkdown = release.get("body")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    .orEmpty()
                val changelogSections = ReleaseChangelogParser.parse(changelogMarkdown)
                RemoteUpdate(
                    releaseId = releaseId,
                    tagName = tagName,
                    versionCode = parsedTag.versionCode,
                    commitShaShort = parsedTag.commitShaShort,
                    assetDownloadUrl = downloadUrl,
                    assetSizeBytes = asset.get("size")?.asLong,
                    releaseName = releaseName,
                    publishedAtEpochMs = publishedEpoch.takeIf { it > 0L },
                    changelogMarkdown = changelogMarkdown,
                    changelogSections = changelogSections,
                )
            }.sortedWith(
                compareByDescending<RemoteUpdate> { it.publishedAtEpochMs ?: 0L }
                    .thenByDescending { it.versionCode }
            )
        }

        internal fun parseLatestMainPrerelease(
            jsonBody: String,
            channel: String,
            assetName: String,
        ): RemoteUpdate? {
            return parseMainPrereleaseHistory(
                jsonBody = jsonBody,
                channel = channel,
                assetName = assetName,
            ).firstOrNull()
        }

        private fun JsonArray.firstAssetByName(assetName: String): JsonObject? {
            return mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject
            }.firstOrNull { asset ->
                asset.get("name")?.asString?.trim() == assetName
            }
        }
    }
}
