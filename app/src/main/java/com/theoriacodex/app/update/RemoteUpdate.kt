package com.theoriacodex.app.update

data class RemoteUpdate(
    val releaseId: Long,
    val tagName: String,
    val versionCode: Int,
    val commitShaShort: String,
    val assetDownloadUrl: String,
    val assetSizeBytes: Long?,
)

data class ParsedMainReleaseTag(
    val versionCode: Int,
    val commitShaShort: String,
)

internal object MainReleaseTagParser {
    fun parse(channel: String, tagName: String): ParsedMainReleaseTag? {
        val pattern = Regex("^${Regex.escape(channel)}-vc(\\d+)-([0-9a-fA-F]{7,40})$")
        val match = pattern.matchEntire(tagName) ?: return null
        val versionCode = match.groupValues[1].toIntOrNull() ?: return null
        if (versionCode <= 0) return null
        val commitShaShort = match.groupValues[2].lowercase()
        return ParsedMainReleaseTag(versionCode = versionCode, commitShaShort = commitShaShort)
    }
}
