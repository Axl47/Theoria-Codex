package com.theoriacodex.app.vision

import android.graphics.Bitmap
import com.theoriacodex.domain.model.Post

interface PictureSource {
    fun hasStill(post: Post): Boolean

    suspend fun still(post: Post): Bitmap?

    fun plays(post: Post): Boolean

    suspend fun frames(post: Post): PostFrames
}

sealed interface PostFrames {
    class Read(val frames: List<Bitmap>) : PostFrames

    data object Unreadable : PostFrames

    data object Unreachable : PostFrames
}
