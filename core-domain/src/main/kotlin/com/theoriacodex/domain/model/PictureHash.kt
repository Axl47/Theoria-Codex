package com.theoriacodex.domain.model

/**
 * Most bits two [Post.pictureHash] values may differ by and still be the same picture. Resizes, re-encodes and other
 * sites' thumbnails of one image all stayed within 4 in the visual-similarity spike; a booru child post often hashes
 * the same as its parent too, so a match means "show once", never "delete".
 */
const val SAME_PICTURE_MAX_DISTANCE = 4

/** How many of the 64 bits [a] and [b] differ in. */
fun pictureDistance(a: Long, b: Long): Int = (a xor b).countOneBits()

fun isSamePicture(a: Long, b: Long): Boolean = pictureDistance(a, b) <= SAME_PICTURE_MAX_DISTANCE

/**
 * Picture hashes by key, answering which stored keys hold the same picture as a hash without comparing it with
 * every entry. The 64 bits are split into five bands; two hashes at most [SAME_PICTURE_MAX_DISTANCE] (4) bits apart
 * agree exactly on at least one band, so only entries sharing a band are compared. Not thread-safe.
 */
class PictureHashIndex<K> {
    private val byBand = Array(BANDS.size) { HashMap<Long, MutableList<Pair<K, Long>>>() }

    fun add(key: K, hash: Long) {
        BANDS.forEachIndexed { band, range -> byBand[band].getOrPut(range.of(hash)) { mutableListOf() } += key to hash }
    }

    /** Keys whose hash is the same picture as [hash], each once. */
    fun matches(hash: Long): Set<K> {
        val found = LinkedHashSet<K>()
        BANDS.forEachIndexed { band, range ->
            byBand[band][range.of(hash)]?.forEach { (key, stored) -> if (isSamePicture(hash, stored)) found += key }
        }
        return found
    }

    /** Adds [key] unless a stored hash is already the same picture as [hash]; returns whether it was added. */
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
        /** Five bands of 13, 13, 13, 13 and 12 bits: one more band than the distance allows differing bits. */
        val BANDS = listOf(Band(0, 13), Band(13, 13), Band(26, 13), Band(39, 13), Band(52, 12))

        init {
            check(BANDS.size > SAME_PICTURE_MAX_DISTANCE) { "A wider match needs more bands" }
        }
    }
}
