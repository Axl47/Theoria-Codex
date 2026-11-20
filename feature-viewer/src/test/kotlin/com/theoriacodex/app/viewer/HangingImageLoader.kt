package com.theoriacodex.app.viewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation

/**
 * Replaces Coil's loader with one whose requests never finish, except the one ending in [loadsImmediately],
 * recording which requests started and were cancelled. Call `Coil.reset()` after the test.
 */
internal fun installHangingLoader(loadsImmediately: String? = null): ImageLoadLog {
    val log = ImageLoadLog()
    val context = ApplicationProvider.getApplicationContext<Application>()
    val image = BitmapDrawable(context.resources, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    Coil.setImageLoader(
        ImageLoader.Builder(context)
            .dispatcher(Dispatchers.Unconfined)
            .components {
                add(
                    Fetcher.Factory<Any> { data, _, _ ->
                        Fetcher {
                            if (loadsImmediately != null && data.toString().endsWith(loadsImmediately)) DrawableResult(image, false, DataSource.MEMORY)
                            else awaitCancellation()
                        }
                    },
                )
            }
            .eventListener(log)
            .build(),
    )
    return log
}

/**
 * [requests], [started] and [cancelled] cover only what is shown on screen. Disk-only warm requests, which never
 * write the memory cache (`memoryCachePolicy.writeEnabled == false`), are kept apart in [warmed].
 */
internal class ImageLoadLog : EventListener {
    val requests = mutableListOf<ImageRequest>()
    val warmed = mutableListOf<ImageRequest>()
    val started get() = requests.map { it.data.toString() }
    val cancelled = mutableListOf<String>()
    override fun onStart(request: ImageRequest) { (if (request.isWarm) warmed else requests) += request }
    override fun onCancel(request: ImageRequest) { if (!request.isWarm) cancelled += request.data.toString() }
    private val ImageRequest.isWarm get() = !memoryCachePolicy.writeEnabled
}
