package com.theoriacodex.extensionhost

import android.content.pm.PackageInfo
import com.theoriacodex.domain.series.SeriesKind
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.sourcePreferences

object AniyomiKind : ExtensionKind {
    override val feature = "tachiyomi.animeextension"
    override val sourceClassesKey = "tachiyomi.animeextension.class"

    override val labelPrefix = "Aniyomi: "
    override val seriesKind = SeriesKind.ANIME

    override fun unsupported(info: PackageInfo): String? {
        val version = info.versionName?.substringBefore('.')?.toIntOrNull()
        return when {
            version == null -> "Unknown extension API"
            version !in SUPPORTED_VERSIONS -> "Needs extension API $version"
            info.applicationInfo?.metaData?.getInt(TORRENT_KEY) == 1 -> "Streams by torrent"
            else -> null
        }
    }

    override fun runs(libVersion: String) = libVersion.substringBefore('.').toIntOrNull() in SUPPORTED_VERSIONS

    override fun adapt(instance: Any, host: HostServices): List<HostedSource>? = when (instance) {
        is AnimeSource -> listOf(instance)
        is AnimeSourceFactory -> instance.createSources()
        else -> null
    }?.map { hosted(it, host) }

    internal fun hosted(source: AnimeSource, host: HostServices): HostedSource {
        val series = AniyomiSeriesSource(source, host)
        return HostedSource(
            series = series,
            name = source.name,
            language = source.lang,
            siteAddress = { (source as? AnimeHttpSource)?.baseUrl },
            settings = (source as? ConfigurableAnimeSource)?.let { SettingsHook({ it.sourcePreferences() }, it::setupPreferenceScreen) },
            images = series.sourceImages,
        )
    }

    private const val TORRENT_KEY = "tachiyomi.animeextension.torrent"
    private val SUPPORTED_VERSIONS = setOf(14, 16)
}
