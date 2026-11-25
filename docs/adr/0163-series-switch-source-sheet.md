# 0163. The Switch source sheet searches other sources with bounded concurrency and locks while a switch runs

Status: Accepted

## Context

Sources answer slowly and share rate limits. Moving a series to another source rewrites its storage, so nothing else on the page may act on the series meanwhile.

## Decision

- Only a few source calls run at once (`MAX_SOURCE_CALLS`, a `Semaphore`); searches and entry counts each take a permit. All calls hang under one parent job, so closing the sheet cancels them.
- Entries of a match are counted as the source lists them from scratch (`stored = emptyList()`); only the count is shown and nothing is stored.
- Candidates are the sources that are on besides the series' own, of the same kind, because progress moves only between lists of the same kind of entry.
- Only the candidates checked under Sources are searched. The store keeps the unchecked source ids (`SwitchSourceSkips`), so a source installed later starts checked. Each toggle saves only the ids it changed, inside the store's update, so two open sheets can't undo each other. Checking a source searches just it; unchecking cancels its search and drops its row.
- While a switch runs the sheet stays open (`dismissible = !sheet.switching`), ignores confirmation, open and retry, and shows only the outcome, so nothing else on the page can be opened until the series has moved or stayed. When another switch moved the series first, only the sheet closes.
- The confirmation says what the switch costs: the current series leaves the shelf, and for manga its downloads are deleted; an anime has no downloads to lose.

## Consequences

The data rules of a switch live in the series storage ADR. Raising the call limit risks tripping source rate limits.

## Applies to

- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SwitchSourceViewModel.kt`: `SwitchSourceViewModel`, `calls`, `searching`, `entryCount`, `switchTo`, `SwitchSourceState`, `toggle`, `keep`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SwitchSourceSheet.kt`: `SwitchSourceSheet`, `switchConsequence`, `SourceChecklist`
- `feature-series/src/main/kotlin/com/theoriacodex/app/series/SwitchSourceChoices.kt`: `sourceGroups`, `toggled`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/Repositories.kt`: `SwitchSourceSkips`
