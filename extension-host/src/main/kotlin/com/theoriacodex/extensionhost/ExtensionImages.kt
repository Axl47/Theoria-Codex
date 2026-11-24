package com.theoriacodex.extensionhost

import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesCovers
import com.theoriacodex.domain.series.isExtensionSource
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

@Suppress("TooGenericExceptionCaught")
internal inline fun <T> failingAsIo(block: () -> T): T = try {
    block()
} catch (failure: IOException) {
    throw failure
} catch (failure: Throwable) {
    if (failure.endsTheProcess()) throw failure
    throw IOException(failure)
}

fun interface SourceImages {
    fun fetch(address: String, url: HttpUrl, data: String?): Response
}

class ExtensionImages(
    private val key: Lazy<ByteArray> = lazy { ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes) },
    private val load: (SourceKey) -> Unit = {},
) : Interceptor, SeriesCovers {
    @Volatile
    private var sources: Map<SourceKey, SourceImages> = emptyMap()

    private val unanswered = ConcurrentHashMap.newKeySet<SourceKey>()

    private val reported = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    internal fun publish(active: List<HostedSource>) {
        sources = active.mapNotNull { source -> source.images?.let { source.key to it } }.toMap()
        unanswered.clear()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun published(source: SourceKey): SourceImages? = sources[source] ?: run {
        if (source in unanswered) return null
        try {
            load(source)
        } catch (failure: Exception) {
            reportOnce("extensions failed to load for an image: ${failure.javaClass.simpleName}")
        }
        sources[source] ?: null.also { unanswered += source }
    }

    override fun address(series: Series): String? {
        if (!series.id.source.isExtensionSource()) return series.coverUrl
        val stored = series.coverUrl?.substringBefore(FRAGMENT) ?: return null
        return tag(series.id.source, stored)
    }

    fun tag(source: SourceKey, address: String, data: String? = null): String {
        val url = address.toHttpUrlOrNull() ?: return address
        return url.newBuilder().fragment(marker(source, url, data)).build().toString()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val fragment = url.fragment.orEmpty()
        if (!fragment.startsWith(TAG)) return chain.proceed(chain.request())
        val untagged = url.newBuilder().fragment(null).build()
        if (chain.request().cacheControl.onlyIfCached) return chain.proceed(chain.request().newBuilder().url(untagged).build())
        val (source, data) = issued(untagged, fragment) ?: return chain.proceed(chain.request().newBuilder().url(untagged).build())
        return failingAsIo { source.fetch(url.toString(), untagged, data) }
    }

    private fun issued(url: HttpUrl, fragment: String): Pair<SourceImages, String?>? {
        val parts = fragment.removePrefix(TAG).split('/', limit = 2)
        val source = SourceKey.parse(parts[0].substringBeforeLast('.')) ?: return null
        val data = parts.getOrNull(1)
        if (!MessageDigest.isEqual(marker(source, url, data).toByteArray(), fragment.toByteArray())) {
            reportOnce("${url.host} image sent without its source: tag not issued here")
            return null
        }
        val images = published(source)
        if (images == null) reportOnce("${url.host} image of source ${source.id} sent without its source: source not on")
        return images?.let { it to data }
    }

    private fun reportOnce(message: String) {
        if (reported.add(message)) Diagnostics.report(LOG_AREA, message)
    }

    private fun marker(source: SourceKey, url: HttpUrl, data: String?): String {
        val signed = "$TAG${source.id}/${data.orEmpty()}\n${url.newBuilder().fragment(null).build()}"
        val code = Mac.getInstance(MAC).apply { init(SecretKeySpec(key.value, MAC)) }.doFinal(signed.toByteArray())
        val shown = code.take(CODE_BYTES).joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }
        return "$TAG${source.id}.$shown" + if (data == null) "" else "/$data"
    }

    internal companion object {
        private const val TAG = "mihon="

        const val FRAGMENT = "#$TAG"

        const val KEY_BYTES = 32
        private const val MAC = "HmacSHA256"
        private const val CODE_BYTES = 16
        private const val BYTE = 0xff
        private const val HEX = 16
        private const val LOG_AREA = "extension-image"
    }
}
