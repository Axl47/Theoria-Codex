# 0146. Save-to-Codex surfaces share one save path, exclude Likes, and rank like the one-tap suggestions

Status: Accepted

## Context

A post can be saved from the picker sheet or by one-tap surfaces, and both must behave the same. Saves must finish after the sheet closes, and Likes is filled only by liking.

## Decision

- `CodexSaver` is the one save path behind the Save-to-Codex picker and quick save, so both behave the same. `CodexSaveViewModel` is activity-owned so save jobs survive sheet dismissal and configuration changes.
- The picker never offers Likes as a target (`SaveToCodexSheet` filters it out before ranking): Likes is filled by liking.
- The picker ranks by relevance like the one-tap suggestions (`rankSaveTargets`) when relevance sorting is on, and by the library sort alone otherwise.
- `SaveSuggestionSource` observes the active profile's Codices, their item lists and covers once and shares them across every post being asked about; each post adds only its relevance lookup. The shared library stops 5 s after its last collector.

## Consequences

Any new save surface must go through `CodexSaver` and `rankSaveTargets` to stay consistent with the others.

## Applies to

- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexSaver.kt`: `CodexSaver`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexSaveViewModel.kt`: `CodexSaveViewModel`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/SaveToCodexSheet.kt`: picker target list and ranking
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/SaveSuggestionSource.kt`: `SaveSuggestionSource`
