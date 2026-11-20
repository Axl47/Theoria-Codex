package com.theoriacodex.sources.hitomi

import java.security.MessageDigest

internal fun IntArray.sortedDistinctCopy(): IntArray = copyOf().sortDistinctOwned()

internal fun IntArray.sortDistinctOwned(): IntArray {
    if (size < 2) return this
    sort()
    var uniqueCount = 1
    for (index in 1..lastIndex) {
        if (this[index] != this[uniqueCount - 1]) {
            this[uniqueCount] = this[index]
            uniqueCount += 1
        }
    }
    return if (uniqueCount == size) this else copyOf(uniqueCount)
}

internal fun IntArray.primitiveSlice(fromIndex: Int, untilIndex: Int): IntArray {
    require(fromIndex in 0..size && untilIndex in fromIndex..size)
    return if (fromIndex == 0 && untilIndex == size) this else copyOfRange(fromIndex, untilIndex)
}

internal class HitomiIntArrayBuilder(initialCapacity: Int) {
    private var values = IntArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(values.size.coerceAtMost(Int.MAX_VALUE / 2) * 2)
        values[size] = value
        size += 1
    }

    fun toIntArray(): IntArray = values.copyOf(size)
}

internal fun IntArray.hitomiSha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    forEach { value ->
        digest.update((value ushr 24).toByte())
        digest.update((value ushr 16).toByte())
        digest.update((value ushr 8).toByte())
        digest.update(value.toByte())
    }
    return digest.digest().toHexString()
}

internal fun ByteArray.hitomiSha256Hex(): String {
    return MessageDigest.getInstance("SHA-256").digest(this).toHexString()
}

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
