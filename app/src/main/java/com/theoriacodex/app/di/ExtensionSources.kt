package com.theoriacodex.app.di

import android.app.Application
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.SourceKey
import okhttp3.Interceptor

/** A source that runs loaded extension code, rather than a built-in adapter or a stored site profile. */
class ExtensionSource(
    /** A custom-source id, so stored posts parse whether or not the extension is loaded. */
    val source: SourceKey,
    val label: String,
    val homeUrl: String,
    val adapter: SourceAdapter,
)

/**
 * Hosts sources backed by third-party extension code. Only debug builds contain an implementation, which the
 * application finds with [java.util.ServiceLoader]; every other build has no extension sources and none of this code.
 */
interface ExtensionSourceHost {
    /** Goes on the app's media clients: answers requests only an extension can fetch and passes the rest on. */
    val mediaInterceptor: Interceptor

    /** Loads the extensions present on the device. Called once per launch, off the main thread. */
    fun load(application: Application): List<ExtensionSource>
}
