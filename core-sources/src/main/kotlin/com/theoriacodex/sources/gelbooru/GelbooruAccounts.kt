package com.theoriacodex.sources.gelbooru

import com.theoriacodex.domain.diagnostics.OffMainWork
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.sources.common.htmlFragmentToText
import com.theoriacodex.sources.common.parseHtml
import com.theoriacodex.sources.common.requireSuccess
import com.theoriacodex.sources.common.sourceCall
import com.theoriacodex.sources.http.SourceHttpClient
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.jsoup.nodes.Element

/**
 * Reads gelbooru.com's profile page (`index.php?page=account&s=profile&id=`):
 * - statistics are `div.row.profileStatSeparator` rows holding a label column then a value column
 *   (Joined as `M/d/yy` or `N/A`, Posts, Favorites);
 * - the avatar is the inline `background` of `div.profileAvatar`, relative to the site root;
 * - the bio is everything after the "About Myself:" section title, which holds rendered HTML.
 *
 * Returns null when the page has none of these, such as an unknown id.
 */
internal fun parseGelbooruAccountProfile(html: String, siteUrl: String): AccountDetails? {
    val document = parseHtml(html, "$siteUrl/")
    val stats = document.select("div.row.profileStatSeparator").mapNotNull { row ->
        val columns = row.children().filter { it.className().startsWith("col-") }
        if (columns.size < 2) null else columns[0].text().trim() to columns[1].text().trim()
    }.toMap()
    val bio = document.select("span.profileSectionTitle")
        .firstOrNull { it.text().startsWith(BIO_TITLE) }
        ?.let(::textAfter)
        ?.ifBlank { null }
    val details = AccountDetails(
        avatarUrl = document.selectFirst("div.profileAvatar")?.let { avatarUrl(it, siteUrl) },
        bio = bio,
        joinedAtEpochMs = stats["Joined"]?.let(::parseProfileDateMs),
        uploadCount = stats["Posts"]?.let(::parseCount),
        favoriteCount = stats["Favorites"]?.let(::parseCount),
    )
    return details.takeIf { it != AccountDetails() }
}

/**
 * The account name gelbooru.com's profile page for [creatorId] searches uploads with (`user:<name>`), or null
 * when the page names none. A failed request throws, so callers can tell a missing name from a failed lookup.
 */
suspend fun fetchGelbooruProfileOwner(
    http: SourceHttpClient,
    creatorId: String,
    headers: Map<String, String>,
): String? = sourceCall {
    val response = http.get(
        GelbooruSite.GELBOORU.apiUrl,
        query = mapOf("page" to "account", "s" to "profile", "id" to creatorId),
        headers = headers,
    )
    parseGelbooruProfileOwner(response.requireSuccess("Gelbooru profile failed").body)
}

private fun parseGelbooruProfileOwner(html: String): String? {
    OffMainWork.note(OffMainWork.SOURCE_PARSE)
    return PROFILE_OWNER.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
}

private val PROFILE_OWNER = Regex("""user:([A-Za-z0-9_:-]+)""", RegexOption.IGNORE_CASE)

/** The text of everything that follows [title] inside its parent. */
private fun textAfter(title: Element): String =
    htmlFragmentToText(generateSequence(title.nextSibling()) { it.nextSibling() }.joinToString("") { it.outerHtml() })

/** Counts print with thousands separators; anything without digits is no count. */
private fun parseCount(value: String): Int? = value.filter(Char::isDigit).toIntOrNull()

private val PROFILE_DATE = DateTimeFormatter.ofPattern("M/d/yy")

/** Midnight UTC of a profile date such as `8/7/07`; null for `N/A` and anything else unreadable. */
private fun parseProfileDateMs(value: String): Long? = try {
    LocalDate.parse(value, PROFILE_DATE).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}

private const val BIO_TITLE = "About Myself"
