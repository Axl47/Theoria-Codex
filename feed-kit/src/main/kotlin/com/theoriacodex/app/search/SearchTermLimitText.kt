package com.theoriacodex.app.search

import com.theoriacodex.app.source.displayName

fun SearchTermLimitNotice.message(): String {
    val name = source.displayName()
    val sort = if (reducedBySort) " with this sort" else ""
    return when {
        blocksSearch -> "$name allows up to ${if (budget == 1) "1 tag" else "$budget tags"} per search$sort."
        budget == 1 -> "Only the first tag goes to $name$sort."
        else -> "Only the first $budget tags go to $name$sort."
    }
}
