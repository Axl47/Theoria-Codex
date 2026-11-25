# 0083. Suggestions interleave providers by relevance band and the omnibox places matches among tag rows

Status: Accepted

## Context
Providers report counts on incomparable scales, and the Search composer has about three rows above the keyboard.

## Decision
- `rankUnifiedTagSuggestions` interleaves one relevance band at a time so every provider stays discoverable; each provider keeps its own count and authoritative-order ranking.
- `rankSearchedTags` treats spellings of one tag type that normalize alike as one tag counted by the source that searched it most; the same value as another type is a separate row.
- Omnibox entries are assembled by the app from its own stores and bind their own actions, so Search needs nothing from the features they lead to (`OmniboxEntry`).
- Text shorter than `SUBSTRING_MIN_LENGTH` only matches word starts because one letter inside words matches nearly everything. Each kind adds at most `OMNIBOX_CAP_PER_KIND` rows.
- `TAG_ROWS_BEFORE_OMNIBOX` is 2 because about three rows fit above the keyboard and the first match must be one of them; `placeOmniboxMatches` puts the rest of the tags after the matches.

## Consequences
Search stays independent of Codex, creator and command features.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/TagSuggestionRanking.kt`: `rankUnifiedTagSuggestions`, `rankSearchedTags`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/omnibox/OmniboxEntry.kt`: `OmniboxEntry`
- `app-logic/src/main/kotlin/com/theoriacodex/app/search/omnibox/OmniboxMatcher.kt`: `SUBSTRING_MIN_LENGTH`, `OMNIBOX_CAP_PER_KIND`, `TAG_ROWS_BEFORE_OMNIBOX`, `placeOmniboxMatches`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/search/TagSuggestionRankingTest.kt`: `unified ranking interleaves providers within each relevance band (ADR 0083)`, `exact and prefix relevance beat incomparable counts (ADR 0083)`, `null-count provider order is stable (ADR 0083)`, `searched tags rank by closeness, then search count, merging spellings within the sources in scope (ADR 0083)`, `searched terms keep their type, rank per type and follow the scope (ADR 0083)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/search/omnibox/OmniboxMatcherTest.kt`: `keywords find an entry and one letter only matches the start of a word (ADR 0083)`, `each kind keeps its best few and equal matches follow the kinds' order (ADR 0083)`, `when anything else matches, only the top two tags come before it (ADR 0083)`
- `scripts/policy_checks.sh`: `module_layering`
- Left to prose: `module_layering` keeps `feature-search` from depending on another feature module; that omnibox entries bind their own actions inside `app-logic` is unguarded, since no check can tell a binding from a dependency there.
