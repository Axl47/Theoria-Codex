package com.theoriacodex.app.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun Context.isMediaNetworkMetered(): Boolean =
    (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered ?: true

fun videoQualityLabel(quality: com.theoriacodex.domain.model.VideoQuality): String = when (quality) {
    com.theoriacodex.domain.model.VideoQuality.AUTO -> "Auto"
    com.theoriacodex.domain.model.VideoQuality.DATA_SAVER -> "Data saver"
    com.theoriacodex.domain.model.VideoQuality.BEST -> "Best available"
}

internal fun Context.animationExportNetworkBlock(settings: com.theoriacodex.data.repository.CacheSettings): String? {
    val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val roaming = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == false
    return if (blockedAnimationExport(settings, isMediaNetworkMetered(), roaming)) {
        "Connect to an allowed network or change download preferences before exporting animation"
    } else null
}
