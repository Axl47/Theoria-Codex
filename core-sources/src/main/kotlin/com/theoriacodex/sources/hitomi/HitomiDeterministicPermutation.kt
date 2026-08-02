package com.theoriacodex.sources.hitomi

/** Stable affine permutation over every index in an arbitrary non-empty source array. */
internal class HitomiDeterministicPermutation(
    private val size: Int,
    seed: Long,
) {
    private val multiplier: Int
    private val increment: Int

    init {
        require(size > 0) { "Hitomi permutation size must be positive" }
        if (size == 1) {
            multiplier = 0
            increment = 0
        } else {
            var candidate = (Math.floorMod(mix64(seed xor MULTIPLIER_SALT), size.toLong() - 1L) + 1L).toInt()
            while (greatestCommonDivisor(candidate, size) != 1) {
                candidate = if (candidate == size - 1) 1 else candidate + 1
            }
            multiplier = candidate
            increment = Math.floorMod(mix64(seed + INCREMENT_SALT), size.toLong()).toInt()
        }
    }

    fun sourceIndex(logicalOffset: Long): Int {
        require(logicalOffset in 0 until size.toLong()) {
            "Hitomi permutation offset must be within the source array"
        }
        if (size == 1) return 0
        return ((multiplier.toLong() * logicalOffset + increment.toLong()) % size.toLong()).toInt()
    }

    private fun greatestCommonDivisor(left: Int, right: Int): Int {
        var a = left
        var b = right
        while (b != 0) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return a
    }

    private fun mix64(input: Long): Long {
        var value = input
        value = (value xor (value ushr 30)) * MIX_MULTIPLIER_ONE
        value = (value xor (value ushr 27)) * MIX_MULTIPLIER_TWO
        return value xor (value ushr 31)
    }

    companion object {
        const val ALGORITHM_VERSION = 1
        private const val MULTIPLIER_SALT = -7046029254386353131L
        private const val INCREMENT_SALT = -3335678366873096957L
        private const val MIX_MULTIPLIER_ONE = -4658895280553007687L
        private const val MIX_MULTIPLIER_TWO = -7723592293110705685L
    }
}
