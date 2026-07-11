package com.theoriacodex.app.ui.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRouteActionsTest {
    @Test
    fun `actions wait for a route owner and flush in order`() {
        val queue = PendingRouteActions<String>()
        val received = mutableListOf<String>()

        assertTrue(queue.dispatchOrEnqueue("first") { false })
        assertTrue(queue.dispatchOrEnqueue("second") { false })

        assertEquals(2, queue.size)
        assertEquals(2, queue.flush { action -> received.add(action) })
        assertEquals(listOf("first", "second"), received)
        assertEquals(0, queue.size)
    }

    @Test
    fun `failed flush retains the remaining ordered work`() {
        val queue = PendingRouteActions<String>()
        queue.dispatchOrEnqueue("first") { false }
        queue.dispatchOrEnqueue("second") { false }
        val firstAttempt = mutableListOf<String>()

        assertEquals(
            1,
            queue.flush { action ->
                firstAttempt += action
                action == "first"
            },
        )

        assertEquals(listOf("first", "second"), firstAttempt)
        assertEquals(1, queue.size)
        val secondAttempt = mutableListOf<String>()
        assertEquals(1, queue.flush { action -> secondAttempt.add(action) })
        assertEquals(listOf("second"), secondAttempt)
    }
}
