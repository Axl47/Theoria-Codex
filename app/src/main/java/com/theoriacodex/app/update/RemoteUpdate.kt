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
    fun parse(channel: String, tagName: String): ParsedMainReleaseTag? {
        val pattern = Regex("^${Regex.escape(channel)}-vc(\\d+)-([0-9a-fA-F]{7,40})$")
        val match = pattern.matchEntire(tagName) ?: return null
        val versionCode = match.groupValues[1].toIntOrNull() ?: return null
        if (versionCode <= 0) return null
        val commitShaShort = match.groupValues[2].lowercase()
        return ParsedMainReleaseTag(versionCode = versionCode, commitShaShort = commitShaShort)
    }
}

internal object ReleaseChangelogParser {
    private val sectionHeadingRegex = Regex("""(?im)^##\s+(.+?)\s*$""")
    private val bulletRegex = Regex("""^\s*[-*]\s+(.+?)\s*$""")
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
                .mapNotNull { line -> bulletRegex.matchEntire(line)?.groupValues?.getOrNull(1)?.trim() }
                .filter { bullet -> bullet.isNotBlank() && !ignoredBulletRegex.matches(bullet) }
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
