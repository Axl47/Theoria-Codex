# 0085. Affinity inputs are built once per profile from its library and rebuilt only when the library changes

Status: Accepted

## Context
Building affinity reads every kept post's tags, and watched posts change constantly. Old Likes were saved with only some tags, and profile switches must not mix one profile's data with another's.

## Decision
- `shareTagAffinityInputs` builds `SharedTagAffinity` in a scope from the first reader, following profile switches. Likes, Codex posts and saved tags are read under the active profile (`flatMapLatest` on the profile), so a switch drops the previous profile's reads; saved searches carry no profile and are shared by every profile. A failed read gives empty inputs and retries with backoff from 1 s doubling to about a minute.
- The kept-library part (`KeptTagEvidence`) rebuilds only when the library changes; statistics are mapped to the counters affinity reads, so writes of usage time or other counters rebuild nothing.
- Library posts are the profile's own Codices including the Likes collection, whose copies hold each Liked post's every tag. A Like saved before every tag was stored kept only the provider's first ones, alphabetically, so the stored copy's tags are merged with the Like's own; a copy saved later with fewer tags does not drop the Like's tags.
- A post both Liked and in a Codex is one kept post. A search counts once under every source it ran on, so a tag's busiest source is its search count.
- `respellChosen` re-spells saved tags and searches for the source For You fills, since they carry no source.
- `latest` is empty until built, and for a moment after a profile switch holds the last profile's; `forProfile` waits out the switch. Only predictions on kept posts count, so analysing a For You candidate changes nothing.

## Consequences
Stored Like data shapes from earlier releases stay supported.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/recommend/TagAffinity.kt`: `tagAffinityInputs`, `KeptTagEvidence`, `respellChosen`, `SharedTagAffinity`, `shareTagAffinityInputs`, `retryDelayMs`

## Guarded by

- `app-logic/src/test/kotlin/com/theoriacodex/app/recommend/SharedTagAffinityTest.kt`: `a profile's inputs wait out a switch that has not reached them (ADR 0085)`, `inputs follow the active profile and read only its own Codices (ADR 0085)`, `a failing library read gives empty inputs at once and retries after a growing pause (ADR 0085)`, `retries keep doubling up to their cap (ADR 0085)`, `a read still running for the previous profile never reaches the latest inputs after a switch (ADR 0085)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/recommend/TagAffinityTest.kt`: `a Like learns every tag of the Library's copy of its post, not only the ones it stored (ADR 0085)`, `a post both Liked and in a Codex is one point of interest (ADR 0085, 0087)`, `one search across several sources is not a repeated search (ADR 0085)`, `a chosen tag's weight moves to its spelling on the source For You fills (ADR 0085)`
- `app-logic/src/test/kotlin/com/theoriacodex/app/recommend/ForYouInterestsTest.kt`: `picture evidence counts kept posts' confident predictions, weighted by score (ADR 0072, 0085)`
- Left to prose: that a usage-time write rebuilds nothing and the kept-library part rebuilds only when the library changes is unguarded: it is a cost choice and the inputs come out the same either way.
