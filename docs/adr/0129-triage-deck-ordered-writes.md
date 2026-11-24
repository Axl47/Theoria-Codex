# 0129. Triage decisions move the card at once and their writes run in order after the deck closes

Status: Accepted

## Context

A swipe must feel instant, yet each decision writes (like, hide, save, remove) and Undo must reverse exactly that write, even when swipes come faster than writes finish or the screen closes.

## Decision

- A decision moves the card on at once; its write and any reversal run one at a time in `writeScope` in the order asked, so a reversal follows its write and a later write follows both. `writeScope` outlives the deck and `close` only stops new writes.
- `decide` does nothing unless the card is still current. Actions are taken with `atDecision()` so a like or hide goes to the profile active when the card was decided.
- A write returns once committed and throws on failure; its reversal undoes exactly what it did. The decision stays recorded until Undo so Undo works while the write still runs. A failed write brings the card back only while it is still the latest decision; after newer swipes it only says so. Owners hear `Decided` before the write and `Reverted` when it no longer stands.
- The card Undo or a failed write restores stays until decided again, even while its hide is still stored.
- A newer copy of a post from the feed replaces the deck's copy (it can carry tags the listing lacked).
- A page counts as loaded even when it adds no posts or none pass the filter, so the deck asks for the next one. A failed page or a pager timeout cancellation is a failed page. `retryPage` does nothing while the feed loads, since some feeds restart when asked mid-request.
- Save suggestions load for the current and next card; a post with no entry is still loading, not empty, and `await` gives up after a short wait.

## Consequences

Decisions never block on the database; failures surface as a returned card. Reordering writes or running them in the deck's own scope would drop pending writes on close.

## Applies to

- `feed-kit/src/main/kotlin/com/theoriacodex/app/triage/TriageDeck.kt`: `TriageDeck`, `TriageDecisionEvent`, `decide`, `undo`, `settle`, `retryPage`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/triage/TriageDeckModel.kt`: `TriageActions`, `TriageDeckState`, `TriageFeedSnapshot`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/triage/TriageSuggestions.kt`: `TriageSuggestions`, `rememberTriageSuggestions`
- `feed-kit/src/main/kotlin/com/theoriacodex/app/trails/TrailPager.kt`: `nextOrNull`, `asTriageFeed`
