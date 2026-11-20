package com.theoriacodex.app.viewer

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import com.theoriacodex.domain.model.Post

fun viewerAspectRatio(post: Post): Float? {
    val width = post.width?.takeIf { it > 0 } ?: return null
    val height = post.height?.takeIf { it > 0 } ?: return null
    return width.toFloat() / height.toFloat()
}
