package com.theoriacodex.app.vision

import com.theoriacodex.domain.vision.PredictedTag
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TagVocabularyTest {
    private val lazyTag = "don't_say_\"lazy\""

    private val csv = listOf(
        "tag_id,name,category,count",
        "9999999,general,9,500",
        "9999998,sensitive,9,300",
        "9999997,questionable,9,100",
        "9999996,explicit,9,100",
        "1,solo,0,600",
        "2,\"don't_say_\"\"lazy\"\"\",0,50",
        "3,\"hello, world\",0,25",
        "4,hatsune_miku,4,100",
    ).joinToString("\n")

    private fun vocabulary(text: String = csv) = TagVocabulary.read(StringReader(text))

    @Test
    fun `predictions keep general and character tags above the floor, strongest first (ADR 0101)`() {
        val scores = floatArrayOf(0.99f, 0.98f, 0.97f, 0.96f, 0.9f, 0.2f, 0.5f, 0.7f)

        assertEquals(
            listOf(
                PredictedTag("solo", 0.9f, PredictedTag.Kind.GENERAL),
                PredictedTag("hatsune_miku", 0.7f, PredictedTag.Kind.CHARACTER),
                PredictedTag("hello, world", 0.5f, PredictedTag.Kind.GENERAL),
            ),
            vocabulary().predictions(scores),
        )
    }

    @Test
    fun `a score exactly at the floor counts and a lower floor admits more`() {
        val scores = floatArrayOf(0f, 0f, 0f, 0f, 0.35f, 0.2f, 0f, 0f)

        assertEquals(listOf("solo"), vocabulary().predictions(scores).map(PredictedTag::tag))
        assertEquals(listOf("solo", lazyTag), vocabulary().predictions(scores, floor = 0.1f).map(PredictedTag::tag))
    }

    @Test
    fun `quoted names keep their commas and doubled quotes (ADR 0101)`() {
        assertNotNull(vocabulary().baseRate(lazyTag))
        assertNotNull(vocabulary().baseRate("hello, world"))
    }

    @Test
    fun `base rate is the count over the four ratings' counts and excludes the rating tags themselves (ADR 0101)`() {
        val words = vocabulary()

        assertEquals(0.6, words.baseRate("solo")!!, 1e-9)
        assertEquals(0.05, words.baseRate(lazyTag)!!, 1e-9)
        assertEquals(0.1, words.baseRate("hatsune_miku")!!, 1e-9)
        assertNull(words.baseRate("explicit"))
        assertNull(words.baseRate("not_a_tag"))
        assertEquals(setOf("solo", lazyTag, "hello, world", "hatsune_miku"), words.baseRates().keys)
    }

    @Test
    fun `a malformed file is rejected (ADR 0101)`() {
        val header = "tag_id,name,category,count\n"

        assertThrows(IllegalStateException::class.java) { vocabulary(header + "1,solo,7,10") }
        assertThrows(IllegalArgumentException::class.java) { vocabulary(header + "1,solo,0") }
        assertThrows(IllegalArgumentException::class.java) { vocabulary(header + "1,solo,0,10\n2,short,0") }
        assertThrows(IllegalArgumentException::class.java) { vocabulary(header + "1,solo,general,10") }
        assertThrows(IllegalArgumentException::class.java) { vocabulary(header) }
    }

    @Test
    fun `scores must match the vocabulary's size (ADR 0101)`() {
        assertThrows(IllegalArgumentException::class.java) { vocabulary().predictions(FloatArray(7)) }
        assertThrows(IllegalArgumentException::class.java) { vocabulary().let { it.predictions(FloatArray(it.size + 1)) } }
    }
}
