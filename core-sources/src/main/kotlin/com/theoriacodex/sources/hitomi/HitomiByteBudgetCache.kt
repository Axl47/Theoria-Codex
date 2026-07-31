package com.theoriacodex.sources.hitomi

/** Thread-safe LRU whose budget and snapshots measure retained payload bytes explicitly. */
internal class HitomiByteBudgetCache<Key, Value>(
    private val maxBytes: Long,
    private val weigh: (Key, Value) -> Long,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry<Value>>(16, 0.75f, true)
    private var cachedBytes = 0L

    init {
        require(maxBytes > 0L) { "Hitomi cache byte budget must be positive" }
    }

    fun get(key: Key): Value? = synchronized(lock) { entries[key]?.value }

    fun latestMatching(predicate: (Key) -> Boolean): Pair<Key, Value>? = synchronized(lock) {
        val key = entries.keys.lastOrNull(predicate) ?: return@synchronized null
        key to requireNotNull(entries[key]).value
    }

    fun put(key: Key, value: Value): Boolean = synchronized(lock) {
        val weight = weigh(key, value)
        require(weight >= 0L) { "Hitomi cache entry weight must be non-negative" }
        entries.remove(key)?.let { previous -> cachedBytes -= previous.weightBytes }
        if (weight > maxBytes) return@synchronized false
        entries[key] = Entry(value, weight)
        cachedBytes = Math.addExact(cachedBytes, weight)
        trimToBudget()
        true
    }

    fun putIfAbsent(key: Key, value: Value): Value? = synchronized(lock) {
        entries[key]?.value?.let { existing -> return@synchronized existing }
        put(key, value)
        null
    }

    fun remove(key: Key): Value? = synchronized(lock) {
        entries.remove(key)?.also { removed -> cachedBytes -= removed.weightBytes }?.value
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        cachedBytes = 0L
    }

    fun snapshot(): HitomiByteBudgetCacheSnapshot<Key> = synchronized(lock) {
        HitomiByteBudgetCacheSnapshot(
            maxBytes = maxBytes,
            cachedBytes = cachedBytes,
            keysInLruOrder = entries.keys.toList(),
            weightsInLruOrder = entries.map { (key, entry) -> key to entry.weightBytes },
        )
    }

    private fun trimToBudget() {
        while (cachedBytes > maxBytes) {
            val eldest = entries.entries.iterator().run {
                if (hasNext()) next() else null
            } ?: break
            entries.remove(eldest.key)
            cachedBytes -= eldest.value.weightBytes
        }
    }

    private data class Entry<Value>(
        val value: Value,
        val weightBytes: Long,
    )
}

internal data class HitomiByteBudgetCacheSnapshot<Key>(
    val maxBytes: Long,
    val cachedBytes: Long,
    val keysInLruOrder: List<Key>,
    val weightsInLruOrder: List<Pair<Key, Long>>,
)

internal fun String.hitomiUtf8ByteWeight(): Long = toByteArray(Charsets.UTF_8).size.toLong()

internal fun IntArray.hitomiByteWeight(): Long {
    return Math.multiplyExact(size.toLong(), Int.SIZE_BYTES.toLong())
}
