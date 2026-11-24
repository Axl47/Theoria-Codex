package com.theoriacodex.app.post

import com.theoriacodex.domain.model.Post

fun Post.displayTitleOrNull(): String? {
    return title
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { candidate -> candidate == id.sourcePostId.trim() }
}
