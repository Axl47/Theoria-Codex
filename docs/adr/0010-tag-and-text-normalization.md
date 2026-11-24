# 0010. Tag spelling is normalized per source dialect and provider quirks are undone at the edge

Status: Accepted

## Context

Persisted tag keys, provider searches and cross-source comparison all depend on how a tag is spelled. Gelbooru-family APIs return HTML-escaped tag names, Pixiv does not use booru underscores or qualifiers, and URL decoding must not apply form semantics.

## Decision

- `TagDialect` of a source never changes, because persisted tag keys depend on it. Every custom site is a booru and uses `UNDERSCORE`.
- `decodeHtmlEntities` decodes in a single pass as HTML does (`&amp;#039;` becomes literal `&#039;`); `normalizeGelbooruToken` decodes entities so escaped API tags compare equal to real names.
- `pixivSearchToken` first trims, drops a leading `-` and collapses whitespace for every token. A token that is all ASCII is then treated as booru-style: underscores become spaces and trailing parenthesized qualifiers are stripped repeatedly (`saber_(fate)` becomes `saber`). A token with any non-ASCII character is passed through with only the shared normalization, so native Japanese tags such as `ラム(リゼロ)` keep their parentheses. This is a heuristic on spelling, not recognition of native tags: an all-ASCII native Pixiv tag ending in parentheses is also stripped.
- `withRecoveredPixivTranslations` upgrades old Pixiv posts: the adapter always stored each translation directly after its native tag, so that order recovers the pairs, and standalone translation terms are dropped.
- `decodePercentEncodedUtf8Strict` keeps a literal `+` and returns null for malformed escapes, unpaired surrogates or malformed UTF-8 rather than a replacement character.

## Consequences

Changing a dialect or the entity pass would orphan stored tags. The Pixiv recovery depends on the historical write order and can be removed once no such posts remain.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/tags/SourceTagNormalization.kt`: `TagDialect`, `tagDialect`, `normalizeGelbooruToken`, `decodeHtmlEntities`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/tags/PixivTags.kt`: `pixivSearchToken`, `withRecoveredPixivTranslations`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/encoding/StrictPercentDecoding.kt`: `decodePercentEncodedUtf8Strict`

## Guarded by

- `core-domain/src/test/kotlin/com/theoriacodex/domain/tags/SourceTagNormalizationTest.kt`: `every built-in source keeps its tag dialect (ADR 0010)`, `custom sources spell tags the booru way (ADR 0004, 0010)`, `html entities decode once and escaped booru tags match their real names (ADR 0010)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/tags/PixivTagsTest.kt`: `search tokens rewrite booru spellings and keep native tags (ADR 0010)`, `legacy pixiv payloads recover translation pairs from canonical order (ADR 0010)`, `posts that already carry translations or have none are unchanged (ADR 0010)`
- `core-domain/src/test/kotlin/com/theoriacodex/domain/encoding/StrictPercentDecodingTest.kt`: `decodes escaped utf8 and preserves literal plus (ADR 0010)`, `rejects malformed escapes and malformed utf8 (ADR 0010)`, `rejects unpaired utf16 surrogates (ADR 0010)`
