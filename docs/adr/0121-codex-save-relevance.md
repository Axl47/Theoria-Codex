# 0121. Codex relevance for save surfaces is shared, warm for a few seconds and never shows placeholders

Status: Accepted

## Context

The hold menu, info sheet and one-tap saves ask about the same posts and search at different times.

## Decision

- Requests are keyed by posts and search; the saved library and its tag rarity are observed once and shared, rescoring the retained snapshot only when the library changes. A request stays warm 5 s after its last collector leaves (carrying scores from the hold menu to the picker); the library stops 5 s after the last request. `SavedLibrary` counts rarity once per snapshot.
- `scores` is null until the first scoring so collectors never see a placeholder.

## Consequences

Surfaces open on existing scores.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/codex/CodexSaveRelevanceLoader.kt`: `CodexSaveRelevanceLoader`, `Lookup`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/codex/CodexSaveRelevanceScoring.kt`: `SavedLibrary`
