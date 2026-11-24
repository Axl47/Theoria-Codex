package com.theoriacodex.domain.adapter

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceAdapterContractTest {
    @Test
    fun `the base adapter declares no optional capability (ADR 0008)`() {
        assertEquals(
            setOf("getSourceKey", "getCapabilities", "search", "trendingTags", "autocompleteTags", "resolvePost"),
            SourceAdapter::class.java.declaredMethods.map { it.name.substringBefore('-') }.toSet(),
        )
    }
}
