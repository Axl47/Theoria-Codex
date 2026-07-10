package com.theoriacodex.sources.hitomi

import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceHttpClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

internal class HitomiGlobalSearchIndex(
    private val httpClient: SourceHttpClient,
) {
    private var version: String? = null

    suspend fun galleryIds(term: String): IntArray {
        val normalized = term.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return IntArray(0)
        val currentVersion = version ?: loadVersion().also { version = it }
        val key = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .copyOf(KEY_BYTES)
        val record = findRecord(currentVersion, key) ?: return IntArray(0)
        return loadGalleryIds(currentVersion, record)
    }

    private suspend fun loadVersion(): String {
        val response = httpClient.get(VERSION_URL, headers = HitomiProtocol.requestHeaders)
        if (response.statusCode != 200) {
            throw HitomiProtocolException("global search version returned HTTP ${response.statusCode}")
        }
        return response.body.trim().takeIf(VERSION_PATTERN::matches)
            ?: throw HitomiProtocolException("global search version was invalid")
    }

    private suspend fun findRecord(version: String, key: ByteArray): DataRecord? {
        var address = 0L
        repeat(MAX_TREE_DEPTH) {
            val node = loadNode(version, address)
            var index = 0
            while (index < node.keys.size && compareKeys(key, node.keys[index]) > 0) index += 1
            if (index < node.keys.size && compareKeys(key, node.keys[index]) == 0) {
                return node.records[index]
            }
            if (node.children.all { child -> child == 0L }) return null
            address = node.children.getOrNull(index)
                ?.takeIf { child -> child > 0L }
                ?: throw HitomiProtocolException("global search B-tree contained an invalid child")
        }
        throw HitomiProtocolException("global search B-tree exceeded its depth limit")
    }

    private suspend fun loadNode(version: String, address: Long): Node {
        val body = rangedGet(
            url = "$INDEX_BASE/galleries.$version.index",
            start = address,
            length = NODE_BYTES,
            maxBodyBytes = NODE_BYTES,
        )
        val buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        val keyCount = buffer.readBoundedInt("key count", 0..MAX_KEYS)
        val keys = ArrayList<ByteArray>(keyCount)
        repeat(keyCount) {
            val size = buffer.readBoundedInt("key size", 1..MAX_KEY_BYTES)
            buffer.requireRemaining(size, "key")
            keys += ByteArray(size).also(buffer::get)
        }
        val recordCount = buffer.readBoundedInt("record count", 0..MAX_KEYS)
        if (recordCount != keyCount) {
            throw HitomiProtocolException("global search B-tree key/record counts disagreed")
        }
        val records = ArrayList<DataRecord>(recordCount)
        repeat(recordCount) {
            buffer.requireRemaining(Long.SIZE_BYTES + Int.SIZE_BYTES, "record")
            val offset = buffer.long
            val length = buffer.int
            if (offset < 0L || length !in 1..MAX_DATA_BYTES) {
                throw HitomiProtocolException("global search B-tree record was out of bounds")
            }
            records += DataRecord(offset, length)
        }
        buffer.requireRemaining(CHILD_COUNT * Long.SIZE_BYTES, "children")
        val children = LongArray(CHILD_COUNT) { buffer.long.also { child ->
            if (child < 0L) throw HitomiProtocolException("global search B-tree child was negative")
        } }
        return Node(keys, records, children)
    }

    private suspend fun loadGalleryIds(version: String, record: DataRecord): IntArray {
        val body = rangedGet(
            url = "$INDEX_BASE/galleries.$version.data",
            start = record.offset,
            length = record.length,
            maxBodyBytes = MAX_DATA_BYTES,
        )
        val buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        buffer.requireRemaining(Int.SIZE_BYTES, "gallery count")
        val count = buffer.int
        if (count !in 1..MAX_GALLERY_IDS || body.size != Int.SIZE_BYTES + count * Int.SIZE_BYTES) {
            throw HitomiProtocolException("global search gallery record had an invalid length")
        }
        return IntArray(count) { buffer.int }
    }

    private suspend fun rangedGet(url: String, start: Long, length: Int, maxBodyBytes: Int): ByteArray {
        val response = httpClient.getBytes(
            url = url,
            headers = HitomiProtocol.requestHeaders,
            range = SourceByteRange(start, Math.addExact(start, length.toLong() - 1L)),
            maxBodyBytes = maxBodyBytes,
        )
        if (response.statusCode !in setOf(200, 206) || response.body.size != length) {
            throw HitomiProtocolException(
                "global search range returned HTTP ${response.statusCode} with ${response.body.size} of $length bytes",
            )
        }
        return response.body
    }

    private fun compareKeys(left: ByteArray, right: ByteArray): Int {
        val size = minOf(left.size, right.size)
        repeat(size) { index ->
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private fun ByteBuffer.readBoundedInt(label: String, range: IntRange): Int {
        requireRemaining(Int.SIZE_BYTES, label)
        return int.takeIf { it in range }
            ?: throw HitomiProtocolException("global search B-tree $label was out of bounds")
    }

    private fun ByteBuffer.requireRemaining(required: Int, label: String) {
        if (remaining() < required) {
            throw HitomiProtocolException("global search B-tree truncated $label")
        }
    }

    private data class Node(
        val keys: List<ByteArray>,
        val records: List<DataRecord>,
        val children: LongArray,
    )

    private data class DataRecord(val offset: Long, val length: Int)

    companion object {
        private const val VERSION_URL = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/version"
        private const val INDEX_BASE = "https://ltn.gold-usergeneratedcontent.net/galleriesindex"
        private const val NODE_BYTES = 464
        private const val MAX_KEYS = 16
        private const val CHILD_COUNT = MAX_KEYS + 1
        private const val MAX_KEY_BYTES = 32
        private const val KEY_BYTES = 4
        private const val MAX_TREE_DEPTH = 64
        private const val MAX_DATA_BYTES = 8 * 1024 * 1024
        private const val MAX_GALLERY_IDS = (MAX_DATA_BYTES - Int.SIZE_BYTES) / Int.SIZE_BYTES
        private val VERSION_PATTERN = Regex("[0-9]+")
    }
}
