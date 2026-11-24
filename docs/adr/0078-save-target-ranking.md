# 0078. Save targets are ranked once, with this session's saves and recent saves lifted above tag relevance

Status: Accepted

## Context
The Save-to-Codex picker and the one-tap save surfaces must agree on which Codex comes first. Saving one post from a search makes it likely that the next ones go to the same Codex, and saves tend to come in runs. Tag matching alone ignores both, and a tag on half the library says almost nothing.

## Decision
- `rankSaveTargets` ranks once and returns `SaveTargets`: `ordered` (Codices already holding the post first, then by score) for the picker and `suggestions` (best Codices not holding it) for one-tap surfaces. Likes and excluded Codices are never targets; ties keep library order.
- `CodexSaveRelevance` scores tag matches weighted by `TagRarity` (inverse document frequency, a tag on every post weighs nothing), over the Codex size plus `SMALL_CODEX_POSTS` so a two-post Codex needs more than one lucky match to lead. Predicted tags with evidence confidence count beside provider tags, weighted by score.
- `SearchSaveMemory` is in memory only and lifts Codices that received saves from the same search to `SEARCH_SAVE_TIER` and above, over every tag score (which stay in 0..1), most used first. A new session starts fresh.
- Recent saves add a boost that closes only part of the gap to `SEARCH_SAVE_TIER` (`RECENT_SAVE_WEIGHT`, fading by about two thirds every `RECENT_SAVE_DECAY_MS`), so a recent save never overtakes a Codex this search already saved into.
- Suggestions break score ties by most recent save, so with no scores the most recently used Codex is first.

## Consequences
Every save surface shows the same order. Search-save memory is lost on restart by design. Changing the score range of tag relevance requires moving `SEARCH_SAVE_TIER` with it.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/codex/CodexSaveRelevance.kt`: `CodexSaveRelevance`, `TagRarity`, `SMALL_CODEX_POSTS`
- `app-logic/src/main/kotlin/com/theoriacodex/app/codex/SaveTargetRanking.kt`: `SaveTargets`, `rankSaveTargets`, `latestSaveTimes`, `RECENT_SAVE_WEIGHT`, `RECENT_SAVE_DECAY_MS`
- `app-logic/src/main/kotlin/com/theoriacodex/app/codex/SearchSaveMemory.kt`: `SearchSaveMemory`, `SEARCH_SAVE_TIER`
