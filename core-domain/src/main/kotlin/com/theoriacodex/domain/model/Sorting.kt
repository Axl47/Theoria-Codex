package com.theoriacodex.domain.model

enum class SortDirection {
    ASCENDING,
    DESCENDING,
    ;

    fun flipped(): SortDirection = if (this == ASCENDING) DESCENDING else ASCENDING
}

data class SortChoice<K>(val key: K, val direction: SortDirection?) {
    fun next(picked: K, natural: (K) -> SortDirection?): SortChoice<K> =
        if (picked == key) copy(direction = direction?.flipped()) else SortChoice(picked, natural(picked))
}

interface DirectedSort<K> {
    val choice: SortChoice<K>
}

fun <K> List<DirectedSort<K>>.sortKeys(): List<K> = map { it.choice.key }.distinct()

fun <S : DirectedSort<K>, K> List<S>.next(current: S, picked: K): S {
    val target = current.choice.next(picked) { key -> first { it.choice.key == key }.choice.direction }
    return firstOrNull { it.choice == target } ?: current
}
