package com.theoriacodex.app.media

import android.content.Context
import android.net.ConnectivityManager

internal fun Context.isMediaNetworkMetered(): Boolean =
    (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered ?: true

internal fun videoQualityLabel(quality: com.theoriacodex.domain.model.VideoQuality): String = when (quality) {
    com.theoriacodex.domain.model.VideoQuality.AUTO -> "Auto"
    com.theoriacodex.domain.model.VideoQuality.DATA_SAVER -> "Data saver"
    com.theoriacodex.domain.model.VideoQuality.BEST -> "Best available"
}

/** Ugoira export uses the application transport rather than Android's queued DownloadManager. */
@Suppress("DEPRECATION") // activeNetworkInfo supplies roaming state on API 26/27 too.
internal fun Context.animationExportNetworkBlock(settings: com.theoriacodex.data.repository.CacheSettings): String? {
    val network = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    return if (blockedAnimationExport(settings, isMediaNetworkMetered(), network?.activeNetworkInfo?.isRoaming == true)) {
        "Connect to an allowed network or change download preferences before exporting animation"
    } else null
}
