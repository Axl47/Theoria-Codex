package com.theoriacodex.app.recommend

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SharedTagAffinityTest {
    @Test
    fun `a profile's inputs wait out a switch that has not reached them`() = runTest {
        val byProfile = MutableSharedFlow<Pair<String, TagAffinityInputs>>(replay = 1)
        val affinity = SharedTagAffinity(byProfile, backgroundScope)
        byProfile.emit("before" to TagAffinityInputs(likes = 1))

        val asked = async { affinity.forProfile("after") }
        runCurrent()
        assertFalse(asked.isCompleted)
        byProfile.emit("after" to TagAffinityInputs(likes = 2))
        assertEquals(2, asked.await().likes)
    }
}
