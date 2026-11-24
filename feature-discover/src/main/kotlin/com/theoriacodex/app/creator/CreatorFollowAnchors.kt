package com.theoriacodex.app.creator

import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.data.repository.followKey
import com.theoriacodex.domain.model.CreatorProfile

object CreatorFollowAnchors {
    private const val PAGE = "creator-follow"
    private const val SHEET = "followed-creators"

    fun page(creator: CreatorProfile) = UndoAnchor(PAGE, creator.followKey())

    fun sheetRow(creator: CreatorProfile) = UndoAnchor(SHEET, creator.followKey())
}
