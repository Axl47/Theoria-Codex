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
