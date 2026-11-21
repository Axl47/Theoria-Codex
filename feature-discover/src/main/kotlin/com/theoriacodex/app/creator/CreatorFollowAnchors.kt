package com.theoriacodex.app.creator

import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.data.repository.followKey
import com.theoriacodex.domain.model.CreatorProfile

/** Where an unfollow's Undo is drawn: in the creator page's Follow button, or in the followed-creators sheet's vacated row. */
object CreatorFollowAnchors {
    private const val PAGE = "creator-follow"
    private const val SHEET = "followed-creators"

    fun page(creator: CreatorProfile) = UndoAnchor(PAGE, creator.followKey())

    fun sheetRow(creator: CreatorProfile) = UndoAnchor(SHEET, creator.followKey())
}
