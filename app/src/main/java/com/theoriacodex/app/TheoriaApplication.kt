package com.theoriacodex.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.theoriacodex.app.di.DefaultTheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.media.LegacyAnimatedWebPDecoder

class TheoriaApplication : Application(), ImageLoaderFactory, TheoriaAppContainerOwner {
    override lateinit var appContainer: TheoriaAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = DefaultTheoriaAppContainer(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(LegacyAnimatedWebPDecoder.Factory())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
}
