package com.theoriacodex.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.theoriacodex.app.media.LegacyAnimatedWebPDecoder
import com.theoriacodex.app.search.FileBackedTagSuggestionStore
import com.theoriacodex.app.search.TagSuggestionStore
import com.theoriacodex.app.search.loadSeedTagSuggestions
import java.io.File

class TheoriaApplication : Application(), ImageLoaderFactory {
    internal val tagSuggestionStore: TagSuggestionStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val storageDirectory = File(filesDir, "theoria_codex")
        FileBackedTagSuggestionStore(
            storeFile = File(storageDirectory, "tag_suggestions.json"),
            seedData = loadSeedTagSuggestions(this),
        )
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
