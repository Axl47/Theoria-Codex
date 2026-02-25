package com.theoriacodex.app.update

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GitHubReleaseFeedClient(
    private val owner: String,
    private val repo: String,
    private val channel: String,
    private val assetName: String,
) : UpdateFeedClient {
    override suspend fun latestMainPrerelease(): Result<RemoteUpdate?> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = "https://api.github.com/repos/$owner/$repo/releases?per_page=20"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TheoriaCodexUpdater")
            }

            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    throw IOException("GitHub update check failed ($status)")
                }
                parseLatestMainPrerelease(
                    jsonBody = body,
                    channel = channel,
                    assetName = assetName,
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        internal fun parseLatestMainPrerelease(
            jsonBody: String,
            channel: String,
            assetName: String,
        ): RemoteUpdate? {
            val root = runCatching { JsonParser.parseString(jsonBody) }.getOrNull() ?: return null
            val releases = root.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

            return releases.mapNotNull { element ->
                val release = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val draft = release.get("draft")?.asBoolean ?: true
                val prerelease = release.get("prerelease")?.asBoolean ?: false
                if (draft || !prerelease) return@mapNotNull null

                val tagName = release.get("tag_name")?.asString?.trim().orEmpty()
                val parsedTag = MainReleaseTagParser.parse(channel = channel, tagName = tagName)
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
                ) to publishedEpoch
            }.maxByOrNull { (_, publishedEpoch) ->
                publishedEpoch
            }?.first
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
