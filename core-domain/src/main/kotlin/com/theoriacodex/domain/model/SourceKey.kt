package com.theoriacodex.domain.model

import com.theoriacodex.domain.source.CustomSourceIds
import com.theoriacodex.domain.source.SiteProfile
import java.util.Locale

@JvmInline
value class SourceKey private constructor(val id: String) : Comparable<SourceKey> {
    val isCustom: Boolean get() = id.startsWith(CustomSourceIds.PREFIX)

    override fun compareTo(other: SourceKey): Int {
        val left = BUILT_IN_ORDER[id]
        val right = BUILT_IN_ORDER[other.id]
        return when {
            left != null && right != null -> left.compareTo(right)
            left != null -> -1
            right != null -> 1
            else -> id.compareTo(other.id)
        }
    }

    override fun toString(): String = id

    companion object {
        val PIXIV = SourceKey("PIXIV")
        val GELBOORU = SourceKey("GELBOORU")
        val AIBOORU = SourceKey("AIBOORU")
        val DANBOORU = SourceKey("DANBOORU")
        val NHENTAI = SourceKey("NHENTAI")
        val HITOMI = SourceKey("HITOMI")
        val IWARA = SourceKey("IWARA")
        val RULE34XXX = SourceKey("RULE34XXX")
        val RULE34PAHEAL = SourceKey("RULE34PAHEAL")
        val RULE34VIDEO = SourceKey("RULE34VIDEO")
        val RULE34GEN = SourceKey("RULE34GEN")

        val builtIns: List<SourceKey> = listOf(
            PIXIV, GELBOORU, AIBOORU, DANBOORU, NHENTAI, HITOMI, IWARA,
            RULE34XXX, RULE34PAHEAL, RULE34VIDEO, RULE34GEN,
        )

        private val BUILT_IN_ORDER: Map<String, Int> = builtIns.withIndex().associate { (index, key) -> key.id to index }

        fun parse(raw: String): SourceKey? {
            val value = raw.trim()
            if (value in BUILT_IN_ORDER) return SourceKey(value)
            val custom = value.lowercase(Locale.ROOT)
            return if (CustomSourceIds.isValid(custom)) SourceKey(custom) else null
        }

        fun parseOrThrow(raw: String): SourceKey =
            parse(raw) ?: throw IllegalArgumentException("Unknown source: ${raw.take(MAX_REPORTED_ID_LENGTH)}")

        private const val MAX_REPORTED_ID_LENGTH = 64

        fun of(profile: SiteProfile): SourceKey = SourceKey(profile.sourceId)
    }
}
