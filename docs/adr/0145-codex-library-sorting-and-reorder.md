# 0145. Codex Library and detail sorts are stable, wait for their inputs, and reorder by long-press drag on the grid

Status: Accepted

## Context

Sorting by usage or animation length depends on data that arrives late or changes while the user is looking (statistics, resolved durations). Showing a first frame in the wrong order, or letting posts move while the Viewer adds watch time, would shift the restored scroll position and make cards jump. Manual reordering also has to survive lazy-grid disposal and out-of-order commits.

## Decision

- Library: `sortCodicesForLibrary` receives Codices in manual order. Automatic sorts pin Likes first because it presents as the full-width Saved tile; usage sorts break ties on the other usage figure, and remaining ties keep manual order. `CodexListUiState.awaitingSortInputs` holds the sort until counts or usage are known, because a first frame in manual order would move the restored scroll anchor.
- `observeCodexLibraryUsage` emits only when a figure changes (other statistics are written often); a statistics failure degrades to no usage, which leaves manual order, and never affects the Library. `codexPostUsage` likewise degrades to no usage and the date order.
- Detail: the store orders only by save time or source (`CodexSortMode.storeOrder`); the app refines the rest in `codexPostRefinement`. Sorting is stable, so ties keep the date order. Length sorts are offered only when the collection has animated posts, unless one is already chosen (`codexSortOptions`).
- Each `CodexSortMode` and `CodexLibrarySort` value is a key plus a direction (`DirectedSort`), and the menus offer one item per key: tapping the selected key picks the value with the opposite direction (`next`), and another key starts at its first-declared value. The values keep their stored names, and every directed key has both directions, so saved sorts, restore state and backups need no migration.
- A length sort compares each post many times and telling an animated post apart reads its media, so `lengthGroups` works out each post's group once per comparator.
- Known durations are recomputed off the main thread as they resolve, so posts settle while resolution runs. The sorted order and known lengths are kept per Codex while the Viewer is open, so an unchanged Codex does not recompute on return. Usage is read once per Codex visit (`CodexUsageSnapshots`) so posts keep their places while the Viewer adds watch time and opens.
- The detail feed reveals in order only for non-length sorts (`revealInOrder = !sortMode.usesDuration`): the filter's reveal keeps shown cards in place, which a length sort must not do as lengths arrive.
- Reordering under Manual sort is a long-press drag handled on the grid (`codexReorderGestures`), not on a tile, so a tile the lazy grid disposes mid-drag never cancels it. A cell waiting to be deleted (`isFixed`) cannot be picked up or swapped. Without `longPressOpensActions` the tile leaves long press to the grid, and only accessibility services open the actions there; releasing a tile where it was lifted opens its menu.
- `CodexGridReorderState`: the finger position is kept in viewport coordinates because scrolling moves cells, not the finger; the swap waits until the grid has laid out the previous swap; when the lifted or target tile is the first visible one, the grid is told to keep its scroll position, because the grid keeps its first visible key in place and would otherwise scroll with the move.
- `bind` ends the pending order when a commit arrived, a Codex came or went, or reordering turned off; a fixed (pending-delete) cell that went is removed from the pending order. `commitFailed` drops a pending order that was never saved, while a newer order awaiting its own commit stays. A drag lifted from an order whose commit then failed keeps its drag state; if it is released unchanged, the grid falls back to the presented order. Releasing a changed order always commits it, even when it equals the presented order, to override a commit of the previous drag still in flight.
- The lifted pointer is consumed in the initial pass (`followFinger`), so neither the grid's scrolling nor the tile's click acts on it. TalkBack reorders through `stepAndCommit` without a drag.

## Consequences

Order never flickers or moves under the user while data arrives, at the cost of holding the first frame until inputs are known. Any new sort needing late data must join `awaitingSortInputs` and the retained-per-visit snapshots.

## Applies to

- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexLibrarySorting.kt`: `sortCodicesForLibrary`, `observeCodexLibraryUsage`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexListUiState.kt`: `awaitingSortInputs`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexPostSorting.kt`: `storeOrder`, `codexPostRefinement`, `lengthGroups`, `rememberCodexPostDurations`, `rememberSortedCodexPosts`, `codexPostUsage`, `CodexUsageSnapshots`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexDetailChips.kt`: `codexSortOptions`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/model/Sorting.kt`: `DirectedSort`, `next`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexDetailScreen.kt`: `rememberCodexDetailFeed`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexGridReorder.kt`: `CodexGridReorderState`, `codexReorderGestures`, `followFinger`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexCoverTile.kt`: `CodexCoverTile`
