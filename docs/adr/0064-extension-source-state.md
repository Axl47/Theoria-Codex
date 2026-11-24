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

## Guarded by

- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ExtensionCallsTest.kt`: `the listed-object map keeps the latest 2048 addresses and forgets the oldest (ADR 0064)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/MihonSeriesSourceTest.kt`: `the source gets its own listed series back, and a stored one rebuilt from its address (ADR 0064)`, `memos the source keeps reach the app and come back when a stored series or chapter is rebuilt (ADR 0007, 0064)`, `a series listed without its memo takes the stored one, and details that give a memo replace it (ADR 0064)`, `details carry the description and Mihon's status, and details without them keep the earlier ones (ADR 0064)`, `details that name only what they add keep the listed title and cover (ADR 0064)`, `a listing is handed the stored chapters, memos included, and the series memo it leaves comes back (ADR 0007, 0064)`, `a chapter listed twice is the first, for the app and for its pages (ADR 0064)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/AniyomiSeriesSourceTest.kt`: `an anime whose details group it into seasons is refused unwrapped, whatever its listing said (ADR 0055, 0064)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/MihonFiltersTest.kt`: `a search sets the values chosen onto a list the source makes afresh (ADR 0064)`, `values that do not line up with the source's list leave its defaults (ADR 0064)`, `later pages of a search reuse the list its first page used, and a new search makes another (ADR 0064)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/AniyomiFiltersTest.kt`: `each of Aniyomi's filters becomes the app's, and a search sets the values chosen onto the source's own (ADR 0064)`
- `extension-host/src/test/kotlin/com/theoriacodex/extensionhost/ChapterRecognitionTest.kt`: `decimals and suffixes stay with their chapter (ADR 0064)`, `a series titled with a number leaves chapter numbers whole (ADR 0064)`, `digits in the series title are not the chapter's (ADR 0064)`
- Left to prose: the listed-object map being synchronized and the first oversized memo being reported once are concurrency and diagnostics details; adapters not rebuilding source objects where state is expected is a rule for future adapters.
