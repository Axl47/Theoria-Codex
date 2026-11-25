package com.theoriacodex.app.series

import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.extensionSourceKey
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

    private fun series(kind: SeriesKind, source: Long, title: String) = Series(SeriesId(kind.extensionSourceKey(source), "/$title"), title)

    private fun extension(name: String, status: RepoExtension.Status, kind: SeriesKind, vararg ids: Long, repo: String = "https://repo.example") =
        RepoExtension(repo, "pkg.$name", name, listOf("en"), "1.0", kind, status, sources = ids.map(kind::extensionSourceKey))
}
