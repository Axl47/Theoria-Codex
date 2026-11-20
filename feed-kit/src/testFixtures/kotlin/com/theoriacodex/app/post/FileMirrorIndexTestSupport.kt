package com.theoriacodex.app.post

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

/** A booru-shaped post whose file URL carries [hash]. */
fun filePost(source: SourceKey, id: String, hash: String): Post = testPost(
    source = source,
    sourcePostId = id,
    full = ImageRef(url = "https://cdn.example/original/ab/cd/$hash.jpg", localPath = null, mime = "image/jpeg"),
)

const val HASH = "0123456789abcdef0123456789abcdef"
const val OTHER = "fedcba9876543210fedcba9876543210"
