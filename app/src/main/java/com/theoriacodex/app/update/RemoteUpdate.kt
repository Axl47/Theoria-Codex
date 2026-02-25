package com.theoriacodex.app.update

data class ChangelogSection(
    val title: String,
    val bullets: List<String>,
)

data class RemoteUpdate(
    val releaseId: Long,
    val tagName: String,
    val versionCode: Int,
    val commitShaShort: String,
    val assetDownloadUrl: String,
    val assetSizeBytes: Long?,
    val releaseName: String? = null,
    val publishedAtEpochMs: Long? = null,
    val changelogMarkdown: String = "",
    val changelogSections: List<ChangelogSection> = emptyList(),
)

data class ParsedMainReleaseTag(
    val versionCode: Int,
    val commitShaShort: String,
)

internal object MainReleaseTagParser {
    private const val SEMVER_VERSION_CODE_BASE = 1_500_000_000
    private const val SEMVER_MAJOR_MULTIPLIER = 10_000
    private const val SEMVER_MINOR_MULTIPLIER = 100

    private val semverPattern = Regex("^v(\\d{1,4})\\.(\\d{1,2})\\.(\\d{1,2})$")
    private val commitishPattern = Regex("^[0-9a-fA-F]{7,40}$")

    fun parse(
        channel: String,
        tagName: String,
        fallbackCommitSha: String? = null,
    ): ParsedMainReleaseTag? {
        val channelPattern = Regex("^${Regex.escape(channel)}-vc(\\d+)-([0-9a-fA-F]{7,40})$")
        val legacyMatch = channelPattern.matchEntire(tagName)
        if (legacyMatch != null) {
            val versionCode = legacyMatch.groupValues[1].toIntOrNull() ?: return null
            if (versionCode <= 0) return null
            val commitShaShort = legacyMatch.groupValues[2].lowercase()
            return ParsedMainReleaseTag(versionCode = versionCode, commitShaShort = commitShaShort)
        }

        val semverMatch = semverPattern.matchEntire(tagName) ?: return null
        val major = semverMatch.groupValues[1].toIntOrNull() ?: return null
        val minor = semverMatch.groupValues[2].toIntOrNull() ?: return null
        val patch = semverMatch.groupValues[3].toIntOrNull() ?: return null
        val versionCode = semverToVersionCode(major = major, minor = minor, patch = patch) ?: return null
        val commitShaShort = normalizeCommitish(fallbackCommitSha) ?: "unknown"
        return ParsedMainReleaseTag(versionCode = versionCode, commitShaShort = commitShaShort)
    }

    private fun semverToVersionCode(major: Int, minor: Int, patch: Int): Int? {
        if (major !in 0..9_999) return null
        if (minor !in 0..99) return null
        if (patch !in 0..99) return null
        val encoded = (major * SEMVER_MAJOR_MULTIPLIER) + (minor * SEMVER_MINOR_MULTIPLIER) + patch
        return SEMVER_VERSION_CODE_BASE + encoded
    }

    private fun normalizeCommitish(fallbackCommitSha: String?): String? {
        val candidate = fallbackCommitSha?.trim().orEmpty()
        if (!commitishPattern.matches(candidate)) return null
        return candidate.lowercase().take(7)
    }
}

internal object ReleaseChangelogParser {
    private val sectionHeadingRegex = Regex("""(?im)^##\s+(.+?)\s*$""")
    private val bulletRegex = Regex("""^(\s*)[-*]\s+(.+?)\s*$""")
    private val fallbackLineRegex = Regex("""^\s*[-*]?\s*(.+?)\s*$""")
    private val ignoredBulletRegex = Regex(
        pattern = """^(tbd|none|n/?a|none reported(?: in this build)?\.?)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val preferredSectionOrder = listOf(
        "highlights",
        "new",
        "improvements",
        "fixes",
        "known issues",
    )

    fun parse(markdown: String): List<ChangelogSection> {
        if (markdown.isBlank()) return emptyList()
        val sections = parseSections(markdown)
        if (sections.isNotEmpty()) return sections
        return parseFallback(markdown)
    }

    private fun parseSections(markdown: String): List<ChangelogSection> {
        val matches = sectionHeadingRegex.findAll(markdown).toList()
        if (matches.isEmpty()) return emptyList()

        val sectionsByName = linkedMapOf<String, ChangelogSection>()
        matches.forEachIndexed { index, match ->
            val rawTitle = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (rawTitle.isBlank()) return@forEachIndexed
            val start = match.range.last + 1
            val end = matches.getOrNull(index + 1)?.range?.first ?: markdown.length
            val body = markdown.substring(start, end)
            val bullets = body.lineSequence()
                .mapNotNull { line ->
                    val lineMatch = bulletRegex.matchEntire(line) ?: return@mapNotNull null
                    val leadingSpaces = lineMatch.groupValues.getOrNull(1)?.length ?: 0
                    val text = lineMatch.groupValues.getOrNull(2)?.trim().orEmpty()
                    if (text.isBlank()) return@mapNotNull null
                    val normalizedIndent = " ".repeat((leadingSpaces / 2) * 2)
                    "$normalizedIndent$text"
                }
                .filter { bullet -> bullet.isNotBlank() && !ignoredBulletRegex.matches(bullet.trim()) }
                .toList()
            if (bullets.isEmpty()) return@forEachIndexed
            val key = rawTitle.lowercase()
            sectionsByName[key] = ChangelogSection(title = rawTitle, bullets = bullets)
        }

        if (sectionsByName.isEmpty()) return emptyList()
        val ordered = mutableListOf<ChangelogSection>()
        preferredSectionOrder.forEach { name ->
            sectionsByName.remove(name)?.let(ordered::add)
        }
        ordered.addAll(sectionsByName.values)
        return ordered
    }

    private fun parseFallback(markdown: String): List<ChangelogSection> {
        val bullets = markdown.lineSequence()
            .mapNotNull { line ->
                val normalized = line.trim()
                if (normalized.isBlank() || normalized.startsWith("#")) {
                    null
                } else {
                    fallbackLineRegex.matchEntire(line)?.groupValues?.getOrNull(1)?.trim()
                }
            }
            .filter { bullet -> bullet.isNotBlank() && !ignoredBulletRegex.matches(bullet) }
            .toList()
        if (bullets.isEmpty()) return emptyList()
        return listOf(
            ChangelogSection(
                title = "Changelog",
                bullets = bullets,
            )
        )
    }
}
