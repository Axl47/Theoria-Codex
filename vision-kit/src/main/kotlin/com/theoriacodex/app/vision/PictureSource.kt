package com.theoriacodex.app.vision

import android.graphics.Bitmap
import com.theoriacodex.domain.model.Post

/**
 * Where the picture tagger's pictures come from. The app reads stills through its image loader and frames through its
 * video cache; tests supply their own.
 */
interface PictureSource {
    /** Whether [post] has a still preview to read. */
    fun hasStill(post: Post): Boolean

    /** [post]'s still preview decoded for the model, or null when it failed to load. */
    suspend fun still(post: Post): Bitmap?

    /** Whether [post] plays (a video or an animation), so its Library analysis reads [frames] beside its still. */
    fun plays(post: Post): Boolean

    /** Frames from across [post]'s video or animation; asked only of posts that [plays]. */
    suspend fun frames(post: Post): PostFrames
}

/** What reading a playing post's frames gave. */
sealed interface PostFrames {
    /** Frames in time order; never empty. */
    class Read(val frames: List<Bitmap>) : PostFrames

    /** No frame can be read (an unreadable file, say); the still is analysed alone. */
    data object Unreadable : PostFrames

    /** The frames could not be read now; a later run tries again. */
    data object Unreachable : PostFrames
}
