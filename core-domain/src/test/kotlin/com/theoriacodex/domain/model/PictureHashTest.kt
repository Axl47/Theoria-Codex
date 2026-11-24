package com.theoriacodex.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureHashTest {
    @Test
    fun `pictures match at 4 differing bits and not at 5 (ADR 0006)`() {
        assertTrue("4 differing bits are one picture", isSamePicture(HASH, HASH xor 0b1111L))
        assertFalse("5 differing bits are two pictures", isSamePicture(HASH, HASH xor 0b11111L))
    }

    @Test
    fun `the banded index finds every hash within the allowed distance (ADR 0006)`() {
        val index = PictureHashIndex<String>().apply { add("stored", HASH) }
        val missed = mutableListOf<Long>()

        fun visit(flips: Long, from: Int, left: Int) {
            if ("stored" !in index.matches(HASH xor flips)) missed += flips
            if (left == 0) return
            for (bit in from until Long.SIZE_BITS) visit(flips or (1L shl bit), bit + 1, left - 1)
        }
        visit(0L, 0, SAME_PICTURE_MAX_DISTANCE)

        assertEquals(emptyList<String>(), missed.take(3).map(java.lang.Long::toBinaryString))
    }

    private companion object {
        const val HASH = 0x5A3C_96E1_0F7B_D248L
    }
}
