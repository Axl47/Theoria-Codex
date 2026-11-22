package com.theoriacodex.app.di

import android.content.Context
import com.theoriacodex.app.media.PreviewPictureHasher
import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope

/**
 * Gives every listed page its picture hashes ([PreviewPictureHasher]) and teaches [mirrors] each copy before listings
 * collapse them, so a copy a listing shows only once stays reachable through "Find on other sites". The source registry
 * passes every page through [annotate], which waits at most [budgetMs] for the page's hashes.
 */
class ListingPictures(
    context: Context,
    scope: CoroutineScope,
    private val mirrors: FileMirrorIndex,
    private val budgetMs: Long = PreviewPictureHasher.LISTING_BUDGET_MS,
) {
    val hasher = PreviewPictureHasher(context, scope)

    suspend fun annotate(posts: List<Post>): List<Post> = hasher.annotate(posts, budgetMs).also(mirrors::learn)
}
