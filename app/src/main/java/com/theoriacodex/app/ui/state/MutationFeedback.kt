package com.theoriacodex.app.ui.state

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class MutationNotice(val id: Long, val message: String)

/** Notices stay with their retained command owner until the UI finishes displaying them. */
internal class MutationFeedback {
    private val sequence = AtomicLong()
    private val pending = MutableStateFlow<List<MutationNotice>>(emptyList())
    val notices = pending.asStateFlow()

    fun add(message: String) {
        val notice = MutationNotice(sequence.incrementAndGet(), message)
        pending.update { it + notice }
    }

    fun acknowledge(id: Long) {
        pending.update { it.filterNot { notice -> notice.id == id } }
    }
}
