# 0143. Codex detail's tag filter is transient, its triage action depends on the collection, and drag-select only runs without a related shelf

Status: Accepted

## Context

Codex detail serves real Codices, Likes and virtual collections (Followed, saved searches) through one screen. Its filters, selection and Triage entry have to make sense for each, and its grid has to keep post indices meaningful.

## Decision

- The tag filter's chosen tags are a quick look inside one Codex: neither a saved filter nor part of the restore record. They and the open sheet survive recreation (`rememberSaveable`), and once the Codex's tags are known for the loaded Codex, chosen tags it no longer offers are dropped. `codexTags` leaves out tags every post carries (they filter nothing) and `isCarriedTag` excludes negated terms, which are exclusions, not tags a post carries.
- Triage from Codex detail removes on a left swipe for a real Codex. Likes keeps the plain browse deck, where left skips: removing there would unlike, which reads wrong beside right meaning like. The action is null for virtual collections, which the deck cannot open; Followed and saved searches pass a custom header, which is how `real = customHeader == null` is decided.
- The tag sheets are held back until tags are known, so the sheet never claims a Codex has none while they load. The site search built from a Codex's tags starts from the source with the most of the chosen tags, keeps its query local until Search (which replaces the current search), and starts over when the source changes, since tags belong to one source's vocabulary.
- "Draw" picks a random saved post's tags (`drawCodexTerms`) so at least that post matches when they are all required; tags drawn independently across a collection may never appear together.
- Drag-select reads grid positions as post indices, so it is enabled only while no related shelf is shifting them. While selecting, the grid's drag-select owns long press. The selection's back handler is registered after the destination's own Back handler so it wins while enabled. The selection marker only draws, because a Surface there would swallow taps meant for the card, which toggles selection.
- The related shelf in a Codex takes only the collection's media filters, and only the collection's own cards resolve posts by id; the shelf shares the card environment without it.
- The unavailable placeholder waits for an answer (`loading`): not answered yet is not the same as gone, and saying so would flash on every first open.

## Consequences

Tag filtering never leaks into saved state. Adding a collection type means deciding its Triage mode and whether it has a real deck. Showing a related shelf disables drag-select by design.

## Applies to

- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexDetailChips.kt`: `rememberCodexTagFilter`, `rememberCodexTriageAction`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexTagFilter.kt`: `codexTags`, `isCarriedTag`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexTagFilterSheet.kt`: `CodexTagSheets`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexTagSearchSheet.kt`: `CodexTagSearchSheet`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexSearchSourceOptions.kt`: `drawCodexTerms`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexDetailScreen.kt`: `CodexUnavailable`, `rememberCodexEditSelection`, `CodexSelectionMarker`, `rememberCodexDetailFeed`
