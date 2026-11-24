package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Post

fun interface LikeTagRewriter {
    suspend fun rewriteLikeTags(merge: (stored: Post, tags: List<String>) -> List<String>): Int
}
