package com.theoriacodex.domain.model

const val SAME_PICTURE_MAX_DISTANCE = 4

fun pictureDistance(a: Long, b: Long): Int = (a xor b).countOneBits()

fun isSamePicture(a: Long, b: Long): Boolean = pictureDistance(a, b) <= SAME_PICTURE_MAX_DISTANCE

class PictureHashIndex<K> {
    private val byBand = Array(BANDS.size) { HashMap<Long, MutableList<Pair<K, Long>>>() }

    fun add(key: K, hash: Long) {
        BANDS.forEachIndexed { band, range -> byBand[band].getOrPut(range.of(hash)) { mutableListOf() } += key to hash }
    }

    fun matches(hash: Long): Set<K> {
        val found = LinkedHashSet<K>()
        BANDS.forEachIndexed { band, range ->
            byBand[band][range.of(hash)]?.forEach { (key, stored) -> if (isSamePicture(hash, stored)) found += key }
        }
        return found
    }

    fun addUnlessMatched(key: K, hash: Long): Boolean {
        val matched = BANDS.withIndex().any { (band, range) ->
            byBand[band][range.of(hash)]?.any { (_, stored) -> isSamePicture(hash, stored) } == true
        }
        if (!matched) add(key, hash)
        return !matched
    }

    private class Band(val shift: Int, bits: Int) {
        private val mask = (1L shl bits) - 1
        fun of(hash: Long): Long = (hash ushr shift) and mask
    }

    private companion object {
        val BANDS = listOf(Band(0, 13), Band(13, 13), Band(26, 13), Band(39, 13), Band(52, 12))

        init {
            check(BANDS.size > SAME_PICTURE_MAX_DISTANCE) { "A wider match needs more bands" }
        }
    }
}
