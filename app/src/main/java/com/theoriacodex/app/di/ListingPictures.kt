package com.theoriacodex.app.di

import android.content.Context
import com.theoriacodex.app.media.PreviewPictureHasher
import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope

class ListingPictures(
    context: Context,
    scope: CoroutineScope,
    private val mirrors: FileMirrorIndex,
    private val budgetMs: Long = PreviewPictureHasher.LISTING_BUDGET_MS,
) {
    val hasher = PreviewPictureHasher(context, scope)

    suspend fun annotate(posts: List<Post>): List<Post> = hasher.annotate(posts, budgetMs).also(mirrors::learn)
}
