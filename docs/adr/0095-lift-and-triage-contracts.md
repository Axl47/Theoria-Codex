# 0095. Card lift and triage hand-offs keep the long-click as the fallback and carry the grid's view

Status: Accepted

## Context

Feed cards can lift into a drag-to-collect gesture or open the triage deck. Both are provided from outside the ui-kit, and a card must behave sanely when nothing provides them.

## Decision

- `LiftController.start` returns false when no lift started, and the card then keeps its long-click. `LiftController.None` never lifts. While `lifting` is true, another card's long press must not start or open anything.
- `TriageSeed` hands the deck what the grid shows when Triage is tapped, as a grid hands posts to the Viewer: visible posts in order, the grid's media, mark and date filters (which a Viewer opened from the deck applies), and `filterInRange`, so a copy with another date range filters by that one.
- `rememberTriageAction` returns null, so the filter sheet offers no Triage, when there is no feed key, the grid shows no posts, or nothing provides a `LocalTriageOpener`.
- The app shell hands a triage deck what the tapped grid showed through `TriageHandOffViewModel`, Activity-scoped so a deck that rotation recreates finds it again. It holds the seed until the next Triage tap replaces it; after process death it is empty and the deck closes, back to its origin feed (`TriageDestination`). Its scope runs each deck's writes, so they finish after the deck closes. A second Triage tap while one is opening replaces the first (`rememberTriageOpener`).
- `openTriage` pushes the deck only once Search's pending filters have applied (`settledTriage`), and only over the screen and tab Triage was tapped on; when the user has moved on meanwhile it opens nothing.
- The deck's owner keeps the feed it was built with; a rebuilt one (`remember(request, seed)`) only supplies the posts to resolve lengths for. While the grid filters by length (`lengthFilterActive`), the deck resolves the lengths of the owner's posts it has yet to show.
- `triageCallbacks`: the picker and the Create Codex dialog hand the chosen Codex back to the deck, which saves it (creating it, when new) itself, so the card moves on and Undo covers it like any other save. A deck that has closed meanwhile saves and creates nothing.
- `triageQuery`: Search is the one feed whose query the deck can read, and only while Search still shows the query the deck opened over; the others rank saves and hide tags without one.
- `openTriagePost`: a live feed's Viewer keeps following its owner while it shows the deck's query, with the filters the grid handed over (`seed`), as a tap on that feed's own grid does, so paging in the deck and in the Viewer share one feed. A Codex-backed deck opens the Codex Viewer instead.
- `AppRoute.Triage` joins Codex ids of `TriageMode.RemoveFromCodex.alsoIn` with `TRIAGE_ID_SEPARATOR` (a comma); Codex ids never contain it.

## Consequences

Cards never lose their menu when lifting is unavailable. Changing the seed means keeping the deck and the Viewer's filtering consistent.

## Applies to

- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/navigation/LiftController.kt`: `LiftController`, `LiftController.None`
- `ui-kit/src/main/kotlin/com/theoriacodex/app/ui/navigation/TriageOpener.kt`: `TriageSeed`, `rememberTriageAction`
- `app/src/main/java/com/theoriacodex/app/ui/TriageDestination.kt`: `TriageHandOffViewModel`, `rememberTriageOpener`, `openTriage`, `TriageDestination`, `triageCallbacks`, `triageQuery`, `openTriagePost`
- `app/src/main/java/com/theoriacodex/app/ui/AppNavigation.kt`: `TRIAGE_ID_SEPARATOR`
