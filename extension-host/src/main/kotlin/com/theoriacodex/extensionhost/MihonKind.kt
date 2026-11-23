package com.theoriacodex.extensionhost

import android.content.pm.PackageInfo
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences

/**
 * Mihon's manga extensions: packages that declare the `tachiyomi.extension` feature and are built against extension
 * API 1.6, the only version the extension index held. Their sources implement `eu.kanade.tachiyomi.source.Source`,
 * presented to the app by [MihonSeriesSource].
 */
object MihonKind : ExtensionKind {
    override val feature = "tachiyomi.extension"
    override val sourceClassesKey = "tachiyomi.extension.class"

    /** Extensions label themselves "Tachiyomi: <name>". */
    override val labelPrefix = "Tachiyomi: "

    override fun unsupported(info: PackageInfo): String? {
        val libVersion = info.applicationInfo?.metaData?.getFloat(LIB_VERSION_KEY)
        return "Needs extension API $libVersion".takeIf { libVersion != SUPPORTED_LIB_VERSION }
    }

    override fun adapt(instance: Any, host: HostServices): List<HostedSource>? = when (instance) {
        is Source -> listOf(instance)
        is SourceFactory -> instance.createSources()
        else -> null
    }?.map { hosted(it, host) }

    /** A source's name, language and id are its code too, so they are read here, within the load's limit. */
    internal fun hosted(source: Source, host: HostServices): HostedSource {
        val key = MihonSeriesSource.sourceKey(source)
        val series = MihonSeriesSource(source, host.images, host.log) { challenged -> host.onChallenged(key, challenged) }
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
