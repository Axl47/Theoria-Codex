package com.theoriacodex.app.series

import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SourceOff
import com.theoriacodex.domain.series.extensionSourceKey
import com.theoriacodex.domain.series.languageName
import java.text.DateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class MissingExtensionsTest {
    @Test
    fun `each missing series goes to the extension that provides its source, or to the unlisted group (ADR 0063)`() {
        val mangaOne = series(SeriesKind.MANGA, 1, "Kaiju")
        val mangaTwo = series(SeriesKind.MANGA, 2, "Dandadan")
        val animeOne = series(SeriesKind.ANIME, 1, "Frieren")
        val unknown = series(SeriesKind.MANGA, 9, "Blue Period")
        val dex = extension("dex", RepoExtension.Status.AVAILABLE, SeriesKind.MANGA, 1, 2)
        val dexElsewhere = extension("dex", RepoExtension.Status.OTHER_KEY, SeriesKind.MANGA, 1, 2, repo = "https://other.example")
        val onsen = extension("onsen", RepoExtension.Status.INSTALLED, SeriesKind.ANIME, 1)

        val groups = missingExtensions(listOf(unknown, animeOne, mangaOne, mangaTwo), listOf(dexElsewhere, onsen, dex))

        assertEquals(listOf(dex to listOf(mangaOne, mangaTwo), onsen to listOf(animeOne)), groups.offered.map { it.extension to it.series })
        assertEquals(listOf(unknown), groups.unlisted)
    }

    @Test
    fun `a site hint is the cover host's registrable domain, or nothing for a cover with no web host`() {
        assertEquals("asurascans.com", siteHint("https://cdn.asurascans.com/asura-images/covers/1.webp"))
        assertEquals("example.co.uk", siteHint("http://img.static.example.co.uk/a.jpg"))
        assertEquals("mangadex.org", siteHint("https://mangadex.org/covers/x.png"))
        assertEquals(null, siteHint("/covers/1.jpg"))
        assertEquals(null, siteHint(null))
        assertEquals(null, siteHint("content://media/1"))
        assertEquals(null, siteHint("http://192.168.1.4/a.jpg"))
        assertEquals(null, siteHint("https://localhost/a.jpg"))
    }

    @Test
    fun `a reading summary counts what was read of the listed entries and when, or says it was not started`() {
        val dates = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val march3 = 1_772_496_000_000L
        fun followed(kind: SeriesKind, total: Int, unread: Int, started: Boolean, read: Long? = null) =
            FollowedSeries(series(kind, 1, "Kaiju"), unreadCount = unread, lastReadAtEpochMs = read, started = started, entryCount = total)

        assertEquals("12 of 40 chapters · read Mar 3, 2026", readingSummary(followed(SeriesKind.MANGA, 40, 28, true, march3), dates))
        assertEquals("40 of 40 chapters · read Mar 3, 2026", readingSummary(followed(SeriesKind.MANGA, 40, 0, true, march3), dates))
        assertEquals("40 chapters · not started", readingSummary(followed(SeriesKind.MANGA, 40, 40, false), dates))
        assertEquals("3 of 12 episodes · watched Mar 3, 2026", readingSummary(followed(SeriesKind.ANIME, 12, 9, true, march3), dates))
        assertEquals(null, readingSummary(followed(SeriesKind.MANGA, 0, 0, false), dates))
    }

    @Test
    fun `an installed copy that is on names the languages that are off, else whether it failed to load or lacks the source`() {
        val kaiju = series(SeriesKind.MANGA, 1, "Kaiju")
        val dandadan = series(SeriesKind.MANGA, 2, "Dandadan")
        val copy = InstalledExtension("/apk/dex", "Ink Dex", "1.0", signer = "key", on = true)
        val off = mapOf(kaiju.id.source to SourceOff("Ink Dex", "es"), dandadan.id.source to SourceOff("Ink Dex", "es"))

        assertEquals(InstalledGap.Off, installedGap(copy.copy(on = false), listOf(kaiju), off))
        assertEquals(InstalledGap.LanguagesOff(listOf("es")), installedGap(copy, listOf(kaiju, dandadan), off))
        assertEquals(InstalledGap.NotLoaded, installedGap(copy.copy(problem = "Unsupported"), listOf(kaiju), off))
        assertEquals(InstalledGap.NotInVersion, installedGap(copy, listOf(kaiju), emptyMap()))
        assertEquals("${languageName("es")} off", languagesOffLine(listOf("es")))
    }

    private fun series(kind: SeriesKind, source: Long, title: String) = Series(SeriesId(kind.extensionSourceKey(source), "/$title"), title)

    private fun extension(name: String, status: RepoExtension.Status, kind: SeriesKind, vararg ids: Long, repo: String = "https://repo.example") =
        RepoExtension(repo, "pkg.$name", name, listOf("en"), "1.0", kind, status, sources = ids.map(kind::extensionSourceKey))
}
