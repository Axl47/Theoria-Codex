# 0054. Source parsers keep provider and stored-data compatibility

Status: Accepted

## Context

Providers return loose or hostile data, and the page-token encoder has changed implementation while keeping its format.

## Decision

- Comment text from HTML fragments is parsed with `htmlFragmentToText` as a fragment: a document parse would move a leading `<title>` into `<head>` and drop its words.
- Danbooru requests send `THEORIA_SOURCE_USER_AGENT` because Cloudflare challenges generic browser agents.
- Hitomi gallery records tolerate numbers as strings, flags as `"1"`/`""` or 1/0, and null lists; tests pin this so a JSON library change cannot turn them into failures.
- The Hitomi page-token decoder must read tokens of the current format version (4) written by the earlier Gson encoder (HTML-escaped strings, fixture `v4-full.txt`); tokens of other format versions are rejected. Tokens live only in memory (see 0043), so no stored data is involved.

## Consequences

Dropping the Gson-encoder compatibility fixture or the lenient parsing is a decision about parser behaviour only.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/common/CommentText.kt`: `htmlFragmentToText`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/danbooru/DanbooruSourceAdapterTest.kt`: `THEORIA_SOURCE_USER_AGENT` check
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiGalleryMetadataTest.kt`: `HitomiGalleryMetadataTest`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiPageTokenCodecTest.kt`: Gson-issued token decoding

## Guarded by

- `core-sources/src/test/kotlin/com/theoriacodex/sources/common/SourceParsingTest.kt`: `comment markup is read as a body fragment (ADR 0054)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/danbooru/DanbooruSourceAdapterTest.kt`: `score search spends a term on its order metatag and keeps numbered pages (ADR 0041, 0054)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/gelbooru/GelbooruCommentsTest.kt`: `Safebooru's column layout is read too (ADR 0042, 0054)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiGalleryMetadataTest.kt`: `reads numbers sent as strings and ignores fields of the wrong type (ADR 0043, 0054)`, `reads tag namespace flags in every shape Hitomi sends (ADR 0043, 0054)`
- `core-sources/src/test/kotlin/com/theoriacodex/sources/hitomi/HitomiPageTokenCodecTest.kt`: `tokens the Gson encoder issued decode the same (ADR 0054)`, `a token of another format version is rejected (ADR 0043, 0054)`
