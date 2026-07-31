package com.theoriacodex.sources.hitomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitomiDeterministicPermutationTest {
    @Test
    fun `affine mapping is deterministic and bijective for awkward source sizes`() {
        val sizes = listOf(
            1,
            2,
            3,
            5,
            7,
            15,
            16,
            17,
            31,
            32,
            33,
            257,
            1_021,
            4_093,
            65_535,
            65_536,
            65_537,
            HitomiNozomi.MAX_GALLERY_IDS,
        )

        sizes.forEach { size ->
            val first = HitomiDeterministicPermutation(size, seed = -7_431_906_221L)
            val repeat = HitomiDeterministicPermutation(size, seed = -7_431_906_221L)
            val seen = BooleanArray(size)
            repeat(size) { offset ->
                val index = first.sourceIndex(offset.toLong())
                assertTrue("$size[$offset] returned $index", index in 0 until size)
                assertTrue("$size repeated source index $index", !seen[index])
                seen[index] = true
                assertEquals(index, repeat.sourceIndex(offset.toLong()))
            }
            assertTrue("$size did not visit every source index", seen.all { it })
        }
    }

    @Test
    fun `mixed coefficients produce diverse starting points and traversal strides`() {
        val size = 257
        val starts = mutableSetOf<Int>()
        val strides = mutableSetOf<Int>()
        val prefixCoverage = mutableSetOf<Int>()

        repeat(64) { seed ->
            val permutation = HitomiDeterministicPermutation(size, seed.toLong())
            val first = permutation.sourceIndex(0)
            val second = permutation.sourceIndex(1)
            starts += first
            strides += Math.floorMod(second - first, size)
            repeat(16) { offset -> prefixCoverage += permutation.sourceIndex(offset.toLong()) }
        }

        assertTrue("seed mixing repeated too many starting points", starts.size >= 50)
        assertTrue("seed mixing repeated too many traversal strides", strides.size >= 50)
        assertTrue("seed prefixes clustered too narrowly", prefixCoverage.size >= 240)
    }
}
