# 0203. A Triage deck opens on the posts its grid showed and follows that one query, with likes and hides written against the profile active at the swipe

Status: Accepted

## Context

Triage opens from a grid (Search, Creator, Codex, Likes, or cleaning's Review) and must work through what that grid was about to show, even though Search applies filter changes lazily and the owner's feed can move to another query while the deck is open.

## Decision

- `triageFeed` seeds the deck from the grid. A live feed (Search, Creator profile) gives the grid's posts in its order, then whatever else of the owner's posts the grid's filter keeps with the lengths known so far, while the owner still shows the deck's query (`queryHash == request.feedKey`); it returns null once the owner no longer shows that query. A Codex or Likes gives exactly the posts its grid showed. Cleaning's Review (`TriageMode.RemoveFromCodex` with Codices to compare) gives exactly the posts cleaning would remove, read once when the deck opens (`cleaningReviewSnapshots`), so removals and saves made in the deck do not reshuffle them. For You, Related, Recents, Trail and Series offer no Triage: For You is a deck itself.
- `seededTriageFeed` stops following the owner as soon as it moves to another query: the deck takes nothing more from it and stops asking for pages (`nextPage` and `retry` check the query hash). The app's decided lengths are read only while the seed's length filter is active.
- `settledTriage` runs before the deck opens: a Search filter change still waiting to run starts now (`SearchAction.SettleSearch`), and its results arrive before the deck opens, so the deck works through the query the grid is about to show, filtered by that query's date range. Other feeds apply their filters on the spot.
- `ShellTriageActions`: likes go straight through the likes workflow (`likeIfAbsent`), not the queued toggle feed cards use, so each call returns once its Like commits and throws when it fails. Hides go to the active profile's For You tag blacklist; single-tag entries are what hiding adds. Each reversal names the profile its write used. `atDecision()` captures the active profile when the swipe is made, because the write runs after earlier ones, by which time the active profile may have changed.

## Consequences

The deck can run out of posts when its grid's query changes underneath it, by design. A new grid that offers Triage must say what its seed is and which feed it follows.

## Applies to

- `app/src/main/java/com/theoriacodex/app/ui/TriageFeeds.kt`: `SeededTriageFeed`, `triageFeed`, `triageRemoval`, `settledTriage`, `seededTriageFeed`, `cleaningReviewSnapshots`, `decidedLengths`, `ShellTriageActions`
