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
