package com.theoriacodex.app.media

enum class DurationDemandAdmission { SCHEDULED, DEFERRED, DECIDED }

/** Owned demand outlives a full work queue. Priority lanes avoid rescanning a large library per completion. */
class DurationDemandBacklog {
    private val entries = mutableMapOf<MediaDurationKey, MutableMap<String, DurationDemand>>()
    private val lanes = DurationDemandPriority.entries.associateWith { linkedSetOf<MediaDurationKey>() }

    fun contains(key: MediaDurationKey): Boolean = key in entries
    fun isEmpty(): Boolean = entries.isEmpty()

    fun put(demands: List<DurationDemand>) {
        demands.forEach { demand ->
            entries.getOrPut(demand.key) { linkedMapOf() }[demand.identity] = demand
            index(demand.key)
        }
    }

    fun takeNext(): List<DurationDemand>? {
        val key = lanes.values.firstNotNullOfOrNull { it.firstOrNull() } ?: return null
        return remove(key)
    }

    fun remove(key: MediaDurationKey): List<DurationDemand> {
        lanes.values.forEach { it.remove(key) }
        return entries.remove(key)?.values?.toList().orEmpty()
    }

    fun removeIdentities(identities: Set<String>): Set<MediaDurationKey> {
        val affected = linkedSetOf<MediaDurationKey>()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val (key, demands) = iterator.next()
            if (!demands.keys.removeAll(identities)) continue
            affected += key
            if (demands.isEmpty()) {
                iterator.remove()
                lanes.values.forEach { it.remove(key) }
            } else index(key)
        }
        return affected
    }

    private fun index(key: MediaDurationKey) {
        val priority = entries.getValue(key).values.minOf(DurationDemand::priority)
        lanes.forEach { (lane, keys) -> if (lane == priority) keys.add(key) else keys.remove(key) }
    }
}
