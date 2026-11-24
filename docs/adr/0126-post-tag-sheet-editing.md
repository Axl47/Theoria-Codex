# 0126. Tag sheets edit the Search draft without silently dropping terms and keep chips layout-stable

Status: Accepted

## Context

Tapping a tag edits a live Search draft whose source may differ, and counts arrive late.

## Decision

- Every addition passes `PostTagEditor.add`, which holds back edits that would lose terms (`termsLostToPostTerm`) until the user confirms; an opposing entry is removed only once the draft accepts the new one. A chip whose level query searches the tag shows active whatever the draft says, but moves still edit the draft.
- A saved entry's key joins tags with a newline because a tag never holds one; a group is only ever required or absent. The tag filter folds case, underscores and runs of spaces.
- Chip counts sit in a fixed slot (at most four characters, `compactTagCount`) so late counts never move chips; compact chips cap their width because weights collapse in unbounded rows; chips are chunked so long lists stay lazy. Counts publish locally first with enrichment off the UI dispatcher.
- A sheet opened collapsed starts scrolled to the item after the folded tags; scroll-to-key waits for the item count to change since the key may first exist then (`LazyListKeyIndex` exists because Compose has no key-to-index lookup).
- `TagSuggestionStore.get` is a compatibility lane for callers understanding only portable general tags.

## Consequences

Adding a tag source needs both edit rules and the count slot.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/tags/PostTagActionSection.kt`: `PostTagEditor`, `ScrollToKeyEffect`, `rememberTagCounts`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/tags/TagPickerModel.kt`: `compactTagCount`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/tags/TagPicker.kt`: `tagChipChunks`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/tags/LazyListKeyIndex.kt`: `LazyListKeyIndex`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/ComposerSuggestionParts.kt`: `FavoriteTagEntry.key`, `FavoriteTagEntry.state`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/tags/TagFilterField.kt`: `normalizedTagFilterText`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/search/TagSuggestionStoreContract.kt`: `get`
