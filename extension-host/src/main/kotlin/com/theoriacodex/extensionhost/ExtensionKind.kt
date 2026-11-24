package com.theoriacodex.extensionhost

import android.content.pm.PackageInfo
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.extensionSourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

interface ExtensionKind {
    val feature: String

    val sourceClassesKey: String

    val labelPrefix: String

    val seriesKind: SeriesKind

    fun sourceKey(id: Long): SourceKey = seriesKind.extensionSourceKey(id)

    fun unsupported(info: PackageInfo): String?

    fun runs(libVersion: String): Boolean

    fun adapt(instance: Any, host: HostServices): List<HostedSource>?
}

class HostedSource(
    val series: SeriesSource,
    val name: String,
    val language: String,
    val siteAddress: () -> String?,
    val settings: SettingsHook?,
    val images: SourceImages?,
) {
    val key: SourceKey get() = series.key
}

class HostServices(
    val images: ExtensionImages,
    val log: (String) -> Unit = {},
    val detached: CoroutineScope = CoroutineScope(SupervisorJob() + detachedDispatcher),
    val onChallenged: (source: SourceKey, host: String) -> Unit = { _, _ -> },
)

private val detachedDispatcher = Dispatchers.IO.limitedParallelism(32)
