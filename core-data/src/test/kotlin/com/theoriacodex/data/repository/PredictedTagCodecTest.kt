package com.theoriacodex.data.repository

import com.theoriacodex.domain.vision.PredictedTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictedTagCodecTest {
    @Test fun `tags with punctuation survive a round trip at per-mille precision (ADR 0017)`() {
        val tags = listOf(
            PredictedTag("hatsune_miku", 0.873f, PredictedTag.Kind.CHARACTER),
            PredictedTag("sky", 0.35f, PredictedTag.Kind.GENERAL),
            PredictedTag("(?)_\"a,b:c\"", 1f, PredictedTag.Kind.GENERAL),
        )

        assertEquals("873 c hatsune_miku\n350 g sky\n1000 g (?)_\"a,b:c\"", PredictedTagCodec.encode(tags))
        assertEquals(tags, PredictedTagCodec.decode(PredictedTagCodec.encode(tags)))
        assertEquals(listOf(PredictedTag("x", 0.7f, PredictedTag.Kind.GENERAL)), PredictedTagCodec.decode(PredictedTagCodec.encode(listOf(PredictedTag("x", 0.7004f, PredictedTag.Kind.GENERAL)))))
        assertTrue(PredictedTagCodec.decode(PredictedTagCodec.encode(emptyList())).isEmpty())
    }

    @Test fun `scores are clamped on encode and malformed lines are skipped on decode (ADR 0017)`() {
        assertEquals("1000 g a\n0 g b", PredictedTagCodec.encode(listOf(PredictedTag("a", 1.4f, PredictedTag.Kind.GENERAL), PredictedTag("b", -0.2f, PredictedTag.Kind.GENERAL))))

        val decoded = PredictedTagCodec.decode("500 g ok\n\nnot a line\n1001 g high\nx g nan\n500 z kind\n500 g\n250 c also_ok")

        assertEquals(listOf("ok", "also_ok"), decoded.map(PredictedTag::tag))
        assertEquals(listOf(0.5f, 0.25f), decoded.map(PredictedTag::score))
    }
}
