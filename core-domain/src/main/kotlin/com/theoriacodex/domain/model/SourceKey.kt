package com.theoriacodex.domain.model

import com.theoriacodex.domain.source.CustomSourceIds
import com.theoriacodex.domain.source.SiteProfile
import java.util.Locale

/**
 * Stable identity of a content source. [id] is the persisted form everywhere (Room `source`
 * columns, JSON stores, backups, credentials) and never changes for a source. Built-ins keep their
 * historical enum names (`PIXIV`, ...); user-added sites use [CustomSourceIds] (`custom_safebooru_org`).
 *
 * A non-null `SourceKey` property compiles to a `String` field, so reflective Gson output (offline
 * manifests) is unchanged from the enum era. Natural order is the old enum order for built-ins,
 * followed by custom sources by id.
 */
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

    /** The persisted id, as the enum's `toString()` returned its name. */
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

        /** Shipped sources in their historical declaration order. */
        val builtIns: List<SourceKey> = listOf(
            PIXIV, GELBOORU, AIBOORU, DANBOORU, NHENTAI, HITOMI, IWARA,
            RULE34XXX, RULE34PAHEAL, RULE34VIDEO, RULE34GEN,
        )

        private val BUILT_IN_ORDER: Map<String, Int> = builtIns.withIndex().associate { (index, key) -> key.id to index }

        /**
         * Parses a persisted id. Built-in ids match exactly, as `valueOf` did. A well-formed custom id
         * parses whether or not a profile exists, so data from a removed site is never dropped. Input
         * is trimmed and custom ids are lowercased; anything else returns null.
         */
        fun parse(raw: String): SourceKey? {
            val value = raw.trim()
            if (value in BUILT_IN_ORDER) return SourceKey(value)
            val custom = value.lowercase(Locale.ROOT)
            return if (CustomSourceIds.isValid(custom)) SourceKey(custom) else null
        }

        /** Strict [parse] for validated documents (backups, job stores): unknown ids throw, as `valueOf` did. */
        fun parseOrThrow(raw: String): SourceKey =
            parse(raw) ?: throw IllegalArgumentException("Unknown source: ${raw.take(MAX_REPORTED_ID_LENGTH)}")

        private const val MAX_REPORTED_ID_LENGTH = 64

        /** Identity of a stored site profile; its stored id is authoritative. */
        fun of(profile: SiteProfile): SourceKey = SourceKey(profile.sourceId)
    }
}
