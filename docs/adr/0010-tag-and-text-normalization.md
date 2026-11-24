# 0010. Tag spelling is normalized per source dialect and provider quirks are undone at the edge

Status: Accepted

## Context

Persisted tag keys, provider searches and cross-source comparison all depend on how a tag is spelled. Gelbooru-family APIs return HTML-escaped tag names, Pixiv does not use booru underscores or qualifiers, and URL decoding must not apply form semantics.

## Decision

- `TagDialect` of a source never changes, because persisted tag keys depend on it. Every custom site is a booru and uses `UNDERSCORE`.
- `decodeHtmlEntities` decodes in a single pass as HTML does (`&amp;#039;` becomes literal `&#039;`); `normalizeGelbooruToken` decodes entities so escaped API tags compare equal to real names.
- `pixivSearchToken` turns booru-style ASCII tokens (`saber_(fate)`) into `saber` but passes native Pixiv tags through unchanged, since their parentheses belong to the tag.
- `withRecoveredPixivTranslations` upgrades old Pixiv posts: the adapter always stored each translation directly after its native tag, so that order recovers the pairs, and standalone translation terms are dropped.
- `decodePercentEncodedUtf8Strict` keeps a literal `+` and returns null for malformed escapes, unpaired surrogates or malformed UTF-8 rather than a replacement character.

## Consequences

Changing a dialect or the entity pass would orphan stored tags. The Pixiv recovery depends on the historical write order and can be removed once no such posts remain.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/tags/SourceTagNormalization.kt`: `TagDialect`, `tagDialect`, `normalizeGelbooruToken`, `decodeHtmlEntities`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/tags/PixivTags.kt`: `pixivSearchToken`, `withRecoveredPixivTranslations`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/encoding/StrictPercentDecoding.kt`: `decodePercentEncodedUtf8Strict`
