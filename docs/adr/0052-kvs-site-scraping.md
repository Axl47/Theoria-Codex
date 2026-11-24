# 0052. KVS sites list latest posts through the listing when RSS thumbnails fail, and read lazy thumbnails from data-rd-src

Status: Accepted

## Context

Kernel Video Sharing sites differ in how they serve latest posts and thumbnails. One site's RSS links thumbnails that answer 404, and listing cards past the first few load lazily.

## Decision

- `KvsLatest.LISTING` is used instead of `RSS` for a site whose RSS thumbnails answer 404.
- `kvsThumbnailUrl` reads `data-original`, then `data-rd-src`, then `src`: lazy cards carry an inline placeholder in `src` and the real address in `data-rd-src`.

## Consequences

Each new KVS site must declare which latest source works. A site whose RSS is fixed can move back to `RSS`.

## Applies to

- `core-sources/src/main/kotlin/com/theoriacodex/sources/rule34/KvsVideoSourceAdapter.kt`: `KvsLatest`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/rule34/Rule34KvsParser.kt`: `kvsThumbnailUrl`

## Guarded by

- `core-sources/src/test/kotlin/com/theoriacodex/sources/rule34/Rule34GenSourceAdapterTest.kt`: `no tags reads the listing page and finds thumbnails behind lazy-load placeholders (ADR 0052)`, `a card's address comes from data-original, then data-rd-src, then src, skipping inline placeholders (ADR 0052)`
- `core-sources/src/main/kotlin/com/theoriacodex/sources/rule34/KvsVideoSourceAdapter.kt`: `enum class KvsVideoSite(val source: SourceKey, val baseUrl: String, val cardSelector: String, val imgPreviewFallback: Boolean, val paging: KvsPaging, val latest: KvsLatest)`
- Left to prose: which latest source works for a new KVS site is checked live.
