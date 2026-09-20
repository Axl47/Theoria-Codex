package com.theoriacodex.data.repository

import com.google.gson.JsonObject
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality

/** Reject malformed external settings rather than invoking legacy migration's lossy fallbacks. */
internal fun validateBackupSettings(root: JsonObject, profileIds: Set<String>) {
    root.backupStrings("enabledSources").forEach { SourceKey.valueOf(it) }
    val weights = requireNotNull(root.getAsJsonObject("sourceWeights"))
    weights.entrySet().forEach { (source, value) ->
        SourceKey.valueOf(source)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
            value.asDouble.isFinite() && value.asDouble >= 0.0) { "Invalid backup source weight" }
    }
    VideoQuality.valueOf(root.backupString("videoQuality"))
    VideoQuality.valueOf(root.backupString("downloadQuality"))
    listOf("downloadsOverMetered", "downloadsOverRoaming", "cacheFullImageOnSave",
        "resolveUnknownAnimatedDurations", "invertMultiImageScrollDirection").forEach { key ->
        val value = root.get(key)
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isBoolean) { "Invalid backup preference: $key" }
    }
    validateProfileTags(root, "favoriteTagsByProfile", profileIds, grouped = false)
    validateProfileTags(root, "forYouBlacklistByProfile", profileIds, grouped = true)
}

private fun validateProfileTags(root: JsonObject, key: String, profileIds: Set<String>, grouped: Boolean) {
    val values = requireNotNull(root.getAsJsonObject(key)) { "Backup is missing $key" }
    values.entrySet().forEach { (profileId, entries) ->
        require(profileId in profileIds && entries.isJsonArray && entries.asJsonArray.size() <= 10_000)
        entries.asJsonArray.forEach { entry ->
            require(entry.isJsonObject)
            SourceKey.valueOf(entry.asJsonObject.backupString("source"))
            if (grouped) {
                val tags = entry.asJsonObject.backupStrings("tags")
                require(tags.isNotEmpty() && tags.all(String::isNotBlank))
            } else {
                entry.asJsonObject.backupString("tag")
            }
        }
    }
}
