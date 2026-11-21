package com.theoriacodex.app.codex

/**
 * [order] with [dragged] moved into [target]'s place, the tiles between shifting one place toward where [dragged]
 * was. [fixed] tiles (a deleted Codex's Undo cell) keep their places and are skipped over. Unchanged when either
 * key is fixed or absent. Used by the Library grid's drag reordering.
 */
internal fun moveTile(order: List<String>, dragged: String, target: String, fixed: Set<String>): List<String> {
    if (dragged == target || dragged in fixed || target in fixed) return order
    val movable = order.filterNot { it in fixed }
    val from = movable.indexOf(dragged)
    val to = movable.indexOf(target)
    if (from < 0 || to < 0) return order
    val moved = movable.toMutableList().apply { add(to, removeAt(from)) }.iterator()
    return order.map { if (it in fixed) it else moved.next() }
}

/**
 * [order] with [key] moved [step] movable places (negative is earlier), for the accessibility moves; null when it
 * cannot move that far.
 */
internal fun stepTile(order: List<String>, key: String, step: Int, fixed: Set<String>): List<String>? {
    val movable = order.filterNot { it in fixed }
    val from = movable.indexOf(key)
    val target = movable.getOrNull(from + step)
    if (from < 0 || step == 0 || target == null) return null
    return moveTile(order, key, target, fixed)
}
