package com.theoriacodex.app.update

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.decodeEachOrSkip
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

class GitHubReleaseFeedClient(
    private val owner: String,
    private val repo: String,
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
        return parseMainPrereleaseHistory(body, assetName)
    }

    companion object {
        internal fun parseMainPrereleaseHistory(
            jsonBody: String,
            assetName: String,
        ): List<RemoteUpdate> {
            val entries = try {
                DurableJson.parseToJsonElement(jsonBody) as? JsonArray
            } catch (_: SerializationException) {
                null
            } ?: return emptyList()

            return entries.decodeEachOrSkip<GitHubRelease>().mapNotNull { release ->
                if (release.draft || !release.prerelease) return@mapNotNull null

                val tagName = release.tagName.trim()
                val parsedTag = MainReleaseTagParser.parse(
                    tagName = tagName,
                    fallbackCommitSha = release.targetCommitish.trim(),
                ) ?: return@mapNotNull null

                val publishedEpoch = runCatching { Instant.parse(release.publishedAt.trim()).toEpochMilli() }.getOrElse { 0L }

                val asset = release.assets.decodeEachOrSkip<GitHubReleaseAsset>().firstOrNull { it.name?.trim() == assetName } ?: return@mapNotNull null
                val downloadUrl = asset.browserDownloadUrl.trim()
                if (downloadUrl.isBlank()) return@mapNotNull null

                val releaseId = release.id ?: return@mapNotNull null
                val releaseName = release.name?.trim()?.takeIf { it.isNotBlank() }
                val changelogMarkdown = release.body.orEmpty()
                val changelogSections = ReleaseChangelogParser.parse(changelogMarkdown)
                RemoteUpdate(
                    releaseId = releaseId,
                    tagName = tagName,
                    versionCode = parsedTag.versionCode,
                    commitShaShort = parsedTag.commitShaShort,
                    assetDownloadUrl = downloadUrl,
                    assetSizeBytes = asset.size,
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
    }
}

@Serializable
private class GitHubRelease(
    @SerialName("id")
    val id: Long? = null,
    @SerialName("draft")
    val draft: Boolean = true,
    @SerialName("prerelease")
    val prerelease: Boolean = false,
    @SerialName("tag_name")
    val tagName: String = "",
    @SerialName("target_commitish")
    val targetCommitish: String = "",
    @SerialName("published_at")
    val publishedAt: String = "",
    @SerialName("name")
    val name: String? = null,
    @SerialName("body")
    val body: String? = null,
    @SerialName("assets")
    val assets: List<JsonElement> = emptyList(),
)

@Serializable
private class GitHubReleaseAsset(
    @SerialName("name")
    val name: String? = null,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String = "",
    @SerialName("size")
    val size: Long? = null,
)
