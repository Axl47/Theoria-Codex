package com.theoriacodex.app.ui.components

fun newPostsLabel(count: Int): String? = when {
    count <= 0 -> null
    count > NEW_POSTS_LABEL_CAP -> "$NEW_POSTS_LABEL_CAP+ new"
    else -> "$count new"
}

private const val NEW_POSTS_LABEL_CAP = 20
