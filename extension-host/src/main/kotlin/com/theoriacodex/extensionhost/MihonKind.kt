package com.theoriacodex.extensionhost

import android.content.pm.PackageInfo
import com.theoriacodex.domain.series.SeriesKind
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences

object MihonKind : ExtensionKind {
    override val feature = "tachiyomi.extension"
    override val sourceClassesKey = "tachiyomi.extension.class"

    override val labelPrefix = "Tachiyomi: "
    override val seriesKind = SeriesKind.MANGA

    override fun unsupported(info: PackageInfo): String? {
        val libVersion = info.applicationInfo?.metaData?.getFloat(LIB_VERSION_KEY)
        return "Needs extension API $libVersion".takeIf { libVersion != SUPPORTED_LIB_VERSION }
    }

    override fun runs(libVersion: String) = libVersion.toFloatOrNull() == SUPPORTED_LIB_VERSION

    override fun adapt(instance: Any, host: HostServices): List<HostedSource>? = when (instance) {
        is Source -> listOf(instance)
        is SourceFactory -> instance.createSources()
        else -> null
    }?.map { hosted(it, host) }

    internal fun hosted(source: Source, host: HostServices): HostedSource {
        val series = MihonSeriesSource(source, host)
        return HostedSource(
            series = series,
            name = source.name,
            language = source.lang,
            siteAddress = { (source as? HttpSource)?.baseUrl },
            settings = (source as? ConfigurableSource)?.let { SettingsHook({ it.sourcePreferences() }, it::setupPreferenceScreen) },
            images = series.sourceImages,
        )
    }

    private const val LIB_VERSION_KEY = "tachiyomix.extensionLib"
    private const val SUPPORTED_LIB_VERSION = 1.6f
}
