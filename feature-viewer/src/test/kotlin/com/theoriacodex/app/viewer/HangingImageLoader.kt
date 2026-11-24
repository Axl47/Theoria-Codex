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
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.network.HttpException
import coil.request.ImageRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

internal fun installHangingLoader(loadsImmediately: String? = null): ImageLoadLog {
    val log = ImageLoadLog()
    val image = BitmapDrawable(context().resources, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
    installTestLoader(log) { data ->
        if (loadsImmediately != null && data.toString().endsWith(loadsImmediately)) DrawableResult(image, false, DataSource.MEMORY)
        else awaitCancellation()
    }
    return log
}

internal fun installGatedLoader(width: Int, height: Int): () -> Unit {
    val gate = CompletableDeferred<Unit>()
    val image = BitmapDrawable(context().resources, Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
    installTestLoader(EventListener.NONE) {
        gate.await()
        DrawableResult(image, false, DataSource.MEMORY)
    }
    return { gate.complete(Unit) }
}

internal fun installFailingLoader(): ImageLoadLog {
    val log = ImageLoadLog()
    installTestLoader(log) { data -> error("No image in tests: $data") }
    return log
}

internal fun installNotFoundLoader() = installTestLoader(EventListener.NONE) { data ->
    val request = Request.Builder().url(data.toString()).build()
    throw HttpException(Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").build())
}

private fun installTestLoader(listener: EventListener, fetch: suspend (Any) -> FetchResult) {
    Coil.setImageLoader(
        ImageLoader.Builder(context())
            .dispatcher(Dispatchers.Unconfined)
            .components { add(Fetcher.Factory<Any> { data, _, _ -> Fetcher { fetch(data) } }) }
            .eventListener(listener)
            .build(),
    )
}

private fun context() = ApplicationProvider.getApplicationContext<Application>()

internal class ImageLoadLog : EventListener {
    val requests = mutableListOf<ImageRequest>()
    val warmed = mutableListOf<ImageRequest>()
    val started get() = requests.map { it.data.toString() }
    val cancelled = mutableListOf<String>()
    override fun onStart(request: ImageRequest) { (if (request.isWarm) warmed else requests) += request }
    override fun onCancel(request: ImageRequest) { if (!request.isWarm) cancelled += request.data.toString() }
    private val ImageRequest.isWarm get() = !memoryCachePolicy.writeEnabled
}
