package com.theoriacodex.app.search

import com.theoriacodex.app.viewer.mediaTestTagPart
import com.theoriacodex.domain.model.PostId

/** Stable semantic identities shared by real feed journeys and renderer benchmarks. */
internal fun searchCardTestTag(postId: PostId): String = "search_card_${postId.mediaTestTagPart()}"

internal fun searchMediaTestTag(postId: PostId): String = "search_media_${postId.mediaTestTagPart()}"

internal fun searchVideoTestTag(postId: PostId): String = "search_video_${postId.mediaTestTagPart()}"
