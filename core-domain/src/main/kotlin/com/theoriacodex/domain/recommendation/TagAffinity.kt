package com.theoriacodex.domain.recommendation

data class TagPair(
    val first: String,
    val second: String,
) {
    companion object {
        fun from(leftRaw: String, rightRaw: String): TagPair? {
            val left = leftRaw.trim()
            val right = rightRaw.trim()
            if (left.isBlank() || right.isBlank()) return null

            val leftKey = left.lowercase()
            val rightKey = right.lowercase()
            if (leftKey == rightKey) return null

            return if (leftKey < rightKey) {
                TagPair(first = left, second = right)
            } else {
                TagPair(first = right, second = left)
            }
        }
    }
}

data class TagAffinityStats(
    val totalDocuments: Int,
    val tagDocumentCounts: Map<String, Int>,
    val pairDocumentCounts: Map<TagPair, Int>,
) {
    fun pairCount(first: String, second: String): Int {
        val pair = TagPair.from(first, second) ?: return 0
        return pairDocumentCounts[pair] ?: 0
    }
}

object TagAffinityBuilder {
    fun build(
        documents: List<List<String>>,
        maxTagsPerDocument: Int = 15,
    ): TagAffinityStats {
        if (maxTagsPerDocument <= 0 || documents.isEmpty()) {
            return TagAffinityStats(
                totalDocuments = 0,
                tagDocumentCounts = emptyMap(),
                pairDocumentCounts = emptyMap(),
            )
        }

        val tagCounts = mutableMapOf<String, Int>()
        val pairCounts = mutableMapOf<TagPair, Int>()
        var totalDocuments = 0

        documents.forEach { document ->
            val normalized = document
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .take(maxTagsPerDocument)
                .toList()
            if (normalized.isEmpty()) return@forEach

            totalDocuments += 1
            normalized.forEach { tag ->
                tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
            }

            normalized.forEachIndexed { index, left ->
                for (offset in index + 1 until normalized.size) {
                    val pair = TagPair.from(left, normalized[offset]) ?: continue
                    pairCounts[pair] = (pairCounts[pair] ?: 0) + 1
                }
            }
        }

        return TagAffinityStats(
            totalDocuments = totalDocuments,
            tagDocumentCounts = tagCounts,
            pairDocumentCounts = pairCounts,
        )
    }
}
