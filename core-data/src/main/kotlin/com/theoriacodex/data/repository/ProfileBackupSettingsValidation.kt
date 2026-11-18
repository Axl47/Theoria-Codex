package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reject malformed external settings rather than invoking legacy migration's lossy fallbacks. */
internal fun validateBackupSettings(root: JsonObject, profileIds: Set<String>) {
    root.backupStrings("enabledSources").forEach { SourceKey.parseOrThrow(it) }
    validateSourceWeights(root.backupObject("sourceWeights"))
    root["sourceWeightsByConfiguration"]?.let { configurations ->
        require(configurations is JsonObject)
        configurations.forEach { (key, weights) ->
            require(weights is JsonObject)
            validateSourceWeights(weights)
            require(key == weights.keys.sorted().joinToString(","))
        }
    }
    if ("viewerPlaybackMode" in root) {
        com.theoriacodex.domain.model.ViewerPlaybackMode.valueOf(root.backupString("viewerPlaybackMode"))
    }
    if ("viewerCommentOrder" in root) com.theoriacodex.domain.model.CommentOrder.valueOf(root.backupString("viewerCommentOrder"))
    if ("codexLibrarySort" in root) CodexLibrarySort.valueOf(root.backupString("codexLibrarySort"))
    VideoQuality.valueOf(root.backupString("videoQuality"))
    VideoQuality.valueOf(root.backupString("downloadQuality"))
    listOf("downloadsOverMetered", "downloadsOverRoaming", "cacheFullImageOnSave",
        "resolveUnknownAnimatedDurations", "invertMultiImageScrollDirection").forEach { key ->
        require(root[key].isBackupBoolean()) { "Invalid backup preference: $key" }
    }
    listOf("instantSeek", "viewerAutoRotateVideos", "oldSearchBar").filter(root::containsKey).forEach { key ->
        require(root[key].isBackupBoolean()) { "Invalid backup preference: $key" }
    }
    validateProfileTags(root, "favoriteTagsByProfile", profileIds, grouped = false)
    validateProfileTags(root, "forYouBlacklistByProfile", profileIds, grouped = true)
}

private fun validateProfileTags(root: JsonObject, key: String, profileIds: Set<String>, grouped: Boolean) {
    val values = root.backupObject(key)
    values.forEach { (profileId, entries) ->
        require(profileId in profileIds && entries is JsonArray && entries.size <= 10_000)
        entries.forEach { entry ->
            require(entry is JsonObject)
            SourceKey.parseOrThrow(entry.backupString("source"))
            if (grouped) {
                val tags = entry.backupStrings("tags")
                require(tags.isNotEmpty() && tags.all(String::isNotBlank))
            } else {
                entry.backupString("tag")
                // A saved "or" group also lists every alternative.
                if (entry["tags"].isPresentValue()) {
                    val tags = entry.backupStrings("tags")
                    require(tags.isNotEmpty() && tags.all(String::isNotBlank))
                }
                if (entry["name"].isPresentValue()) entry.backupString("name")
            }
        }
    }
}

private fun validateSourceWeights(weights: JsonObject) {
    weights.forEach { (source, value) ->
        SourceKey.parseOrThrow(source)
        val weight = (value as? JsonPrimitive)?.content?.toDoubleOrNull()
        require(value.isBackupNumber() && weight != null && weight.isFinite() && weight >= 0.0) {
            "Invalid backup source weight"
        }
    }
}

/** A key that is present with a non-null value. */
private fun JsonElement?.isPresentValue() = this != null && this !is JsonNull
