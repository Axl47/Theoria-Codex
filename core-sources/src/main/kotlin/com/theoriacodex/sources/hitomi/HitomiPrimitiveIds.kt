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

internal data class HitomiIdAllocationModel(
    val idCount: Int,
    val primitiveIdBytes: Long,
    val legacyBoxedDecodePeakBytes: Long,
    val commonInlineSourceBytes: Long,
    val legacyInlineExtraBytes: Long,
    val newInlineExtraBytes: Long,
    val legacyPerSeedRetainedShuffleBytes: Long,
    val newPerSeedRetainedShuffleBytes: Long,
    val statelessPermutationBytes: Long,
) {
    val nozomiPeakBytesSaved: Long = legacyBoxedDecodePeakBytes - primitiveIdBytes
    val inlinePeakBytesSaved: Long = legacyInlineExtraBytes - newInlineExtraBytes

    companion object {
        fun forIdCount(idCount: Int): HitomiIdAllocationModel {
            require(idCount >= 0)
            val primitive = INT_ARRAY_HEADER_BYTES + idCount.toLong() * Int.SIZE_BYTES
            val boxedList = OBJECT_ARRAY_HEADER_BYTES + idCount.toLong() * COMPRESSED_REFERENCE_BYTES
            val boxedIntegers = idCount.toLong() * BOXED_INTEGER_BYTES
            return HitomiIdAllocationModel(
                idCount = idCount,
                primitiveIdBytes = primitive,
                legacyBoxedDecodePeakBytes = primitive + boxedList + boxedIntegers,
                commonInlineSourceBytes = primitive,
                legacyInlineExtraBytes = primitive + byteArrayBytes(idCount),
                newInlineExtraBytes = primitive + TWO_LONG_STATE_BYTES,
                legacyPerSeedRetainedShuffleBytes = primitive,
                newPerSeedRetainedShuffleBytes = 0L,
                statelessPermutationBytes = TWO_LONG_STATE_BYTES,
            )
        }

        private const val INT_ARRAY_HEADER_BYTES = 16L
        private const val BYTE_ARRAY_HEADER_BYTES = 16L
        private const val OBJECT_ARRAY_HEADER_BYTES = 16L
        private const val COMPRESSED_REFERENCE_BYTES = 4L
        private const val BOXED_INTEGER_BYTES = 16L
        private const val TWO_LONG_STATE_BYTES = 16L

        private fun byteArrayBytes(idCount: Int): Long {
            return BYTE_ARRAY_HEADER_BYTES + idCount.toLong() * Int.SIZE_BYTES
        }
    }
}
