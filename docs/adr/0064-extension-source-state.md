# 0064. Adapters hand sources back their own objects, memos and filters, and merge details additively

Status: Accepted

## Context

Mihon and Aniyomi sources keep state on the objects they list (`SManga`, `SAnime`, chapters, memos, filter lists) and expect to receive those objects back. The app stores only plain series and entries, and an earlier run's objects are gone.

## Decision

- `recentlyListed` keeps the most recent listed objects by address in a synchronized map; a later call hands the source its own object, and an object from an earlier run is rebuilt from what the app stored (Mihon: memo included; a held one listed without a memo takes the stored memo).
- Details only add to the series or anime the app asked about (`withDetails`): blank title/cover/address are ignored, and a title has no value until the source sets one. An already-opened anime keeps what its details said, seasons included.
- Mihon's `entries` gives the source its stored chapters with memos; the returned series' memo is kept even when cleared, since some sources record state while listing. A memo is read before the call because the source may change the object in place; the held object takes the returned copy's memo. A chapter listed twice is the first.
- `storedMemo`: a memo longer than `MAX_MEMO_LENGTH` is stored as `{}` (replaces the old memo where null would keep it) so after a restart the source gets none rather than a stale one; the first is reported. `sourceMemo` treats non-object JSON as none so a damaged row or import cannot fail the call.
- Filters (`ExtensionFilters`, `FilterSearch`): Mihon and Aniyomi trees share shapes; a `FilterNode` accepts only a value of its own kind and skips others. Page 1 sets chosen values on a fresh list; later pages of the same search reuse it, as Mihon and Aniyomi do, since a source that fills filters after its first request would otherwise page with other criteria.
- `ChapterRecognition` derives a chapter number from the name, as Mihon does: "Chapter 4 extra" is 4.99 so extras sort after their chapter. The series title is removed only when standing alone ("86" keeps "Chapter 186"); if no number remains, the title was the number and the name is read whole.

## Consequences

Adapters must not rebuild source objects from scratch where state is expected. Memo size limit is the safeguard against unbounded storage.

## Applies to

- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionCalls.kt`: `recentlyListed`, `storedMemo`, `sourceMemo`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/MihonSeriesSource.kt`: `entries`, `withDetails`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/AniyomiSeriesSource.kt`: `withDetails`, `listedAnime`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionFilters.kt`: `FilterNode`, `filterNode`, `FilterSearch`
- `extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ChapterRecognition.kt`: `ChapterRecognition`, `parseChapterNumber`
