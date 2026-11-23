package com.theoriacodex.extensionhost

import android.content.pm.PackageInfo
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesSource

/**
 * One extension interface this host runs, such as Mihon's for manga. It says how to recognize an extension of its kind
 * from what the package manager reports, and turns the classes such an extension names into [HostedSource]s. Everything
 * else ([ExtensionPackages], [ExtensionLoader], [ExtensionRegistry], [ExtensionImages]) works the same for every kind,
 * so a new interface is a new implementation of this, handed to [ExtensionPackages] beside the others.
 */
interface ExtensionKind {
    /** The feature an extension of this kind declares in its manifest (`<uses-feature>`). An APK that declares none is not one. */
    val feature: String

    /** The manifest metadata that names its source classes: separated by `;`, a leading `.` relative to its package. */
    val sourceClassesKey: String

    /** What its label starts with, cut from the name the Extensions page shows. */
    val labelPrefix: String

    /**
     * Why this host cannot run the extension [info] describes, such as an interface version it does not provide; null
     * when it can. Reads what the package manager reports (metadata, version name) and runs none of its code.
     */
    fun unsupported(info: PackageInfo): String?

    /**
     * The sources of [instance], a newly created object of one of the extension's source classes: a source, or a
     * factory of several. Null when it is neither, which rejects the extension. Runs extension code (a factory, a
     * source's name, language and id), within the load's time limit; whatever that code throws rejects the extension.
     */
    fun adapt(instance: Any, host: HostServices): List<HostedSource>?
}

/**
 * One source of a loaded extension, whichever interface it implements: what [ExtensionRegistry] offers, lists and
 * configures, and what [ExtensionImages] fetches through. Made by its [ExtensionKind] when the extension loads.
 */
class HostedSource(
    /** The source as the app sees it; its key is the source's identity across the app. */
    val series: SeriesSource,
    /** As the source reported them when it loaded. */
    val name: String,
    val language: String,
    /**
     * The address of the site it calls, for tracing a browser check back to it; null when it has none. Runs extension
     * code, since an address can depend on its settings, so it can throw.
     */
    val siteAddress: () -> String?,
    /** Its settings; null when it has none. */
    val settings: SettingsHook?,
    /** How its images are fetched through its own client; null when it hands out none that need to be. */
    val images: SourceImages?,
) {
    val key: SourceKey get() = series.key
}

/**
 * What the host gives each source an [ExtensionKind] adapts: [images] to tag the addresses it hands out, [log], and
 * [onChallenged], to hear of each host whose browser check stopped one of the source's calls.
 */
class HostServices(
    val images: ExtensionImages,
    val log: (String) -> Unit = {},
    val onChallenged: (source: SourceKey, host: String) -> Unit = { _, _ -> },
)
