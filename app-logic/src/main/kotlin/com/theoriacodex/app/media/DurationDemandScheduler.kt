package com.theoriacodex.app.media

data class DurationExecutionGate(
    val lifecycleStarted: Boolean,
    val scrollIdle: Boolean,
)

data class ScheduledDurationWork(
    val key: MediaDurationKey,
    val priority: DurationDemandPriority,
    val demands: List<DurationDemand>,
)

sealed interface DurationDemandSubmission {
    data class Accepted(val evictedKey: MediaDurationKey? = null) : DurationDemandSubmission
    data object Rejected : DurationDemandSubmission
}

/** Platform-free bounded queue. Active work and cancellation are owned by the app coordinator. */
class DurationDemandScheduler(
    private val maxQueuedKeys: Int = DEFAULT_MAX_QUEUED_KEYS,
) {
    private val entries = linkedMapOf<MediaDurationKey, QueueEntry>()
    private var sequence = 0L

    init {
        require(maxQueuedKeys > 0) { "Duration queue bound must be positive" }
    }

    fun submit(demand: DurationDemand): DurationDemandSubmission {
        val existing = entries[demand.key]
        if (existing != null) {
            existing.tickets[demand.identity] = Ticket(demand, nextSequence())
            return DurationDemandSubmission.Accepted()
        }

        var evictedKey: MediaDurationKey? = null
        if (entries.size >= maxQueuedKeys) {
            val worst = entries.values.maxWithOrNull(ENTRY_COMPARATOR)
                ?: return DurationDemandSubmission.Rejected
            if (demand.priority.ordinal >= worst.effectivePriority().ordinal) {
                return DurationDemandSubmission.Rejected
            }
            evictedKey = worst.key
            entries.remove(worst.key)
        }

        entries[demand.key] = QueueEntry(
            key = demand.key,
            tickets = linkedMapOf(demand.identity to Ticket(demand, nextSequence())),
        )
        return DurationDemandSubmission.Accepted(evictedKey)
    }

    fun takeNext(gate: DurationExecutionGate): ScheduledDurationWork? {
        if (!gate.lifecycleStarted) return null
        val next = entries.values
            .asSequence()
            .filter { entry ->
                entry.effectivePriority() != DurationDemandPriority.BACKGROUND_IDLE || gate.scrollIdle
            }
            .minWithOrNull(ENTRY_COMPARATOR)
            ?: return null
        entries.remove(next.key)
        return ScheduledDurationWork(
            key = next.key,
            priority = next.effectivePriority(),
            demands = next.tickets.values
                .sortedBy(Ticket::sequence)
                .map(Ticket::demand),
        )
    }

    fun removeIdentity(identity: String): Set<MediaDurationKey> {
        val affected = linkedSetOf<MediaDurationKey>()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            if (entry.tickets.remove(identity) != null) affected += entry.key
            if (entry.tickets.isEmpty()) iterator.remove()
        }
        return affected
    }

    fun removeKey(key: MediaDurationKey): List<DurationDemand> {
        return entries.remove(key)?.tickets?.values
            ?.sortedBy(Ticket::sequence)
            ?.map(Ticket::demand)
            .orEmpty()
    }

    fun contains(key: MediaDurationKey): Boolean = key in entries

    fun queuedKeyCount(): Int = entries.size

    fun queuedDemandCount(): Int = entries.values.sumOf { entry -> entry.tickets.size }

    fun demandsFor(key: MediaDurationKey): List<DurationDemand> {
        return entries[key]?.tickets?.values
            ?.sortedBy(Ticket::sequence)
            ?.map(Ticket::demand)
            .orEmpty()
    }

    private fun nextSequence(): Long {
        sequence += 1L
        return sequence
    }

    private data class Ticket(
        val demand: DurationDemand,
        val sequence: Long,
    )

    private data class QueueEntry(
        val key: MediaDurationKey,
        val tickets: LinkedHashMap<String, Ticket>,
    ) {
        fun effectivePriority(): DurationDemandPriority {
            return tickets.values.minOf { ticket -> ticket.demand.priority }
        }

        fun effectiveSequence(): Long {
            val priority = effectivePriority()
            return tickets.values
                .asSequence()
                .filter { ticket -> ticket.demand.priority == priority }
                .minOf(Ticket::sequence)
        }
    }

    private companion object {
        const val DEFAULT_MAX_QUEUED_KEYS = 512

        val ENTRY_COMPARATOR = compareBy<QueueEntry>(
            { entry -> entry.effectivePriority().ordinal },
            QueueEntry::effectiveSequence,
            { entry -> entry.key.postId.source.ordinal },
            { entry -> entry.key.postId.sourcePostId },
            { entry -> entry.key.mediaFingerprint },
        )
    }
}
