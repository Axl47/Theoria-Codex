# 0085. Affinity inputs are built once per profile from its library and rebuilt only when the library changes

Status: Accepted

## Context
Building affinity reads every kept post's tags, and watched posts change constantly. Old Likes were saved with only some tags, and profile switches must not mix one profile's data with another's.

## Decision
- `shareTagAffinityInputs` builds `SharedTagAffinity` in a scope from the first reader, following profile switches. Everything kept is read under one profile (`flatMapLatest` on the active profile). A failed read gives empty inputs and retries with backoff from 1 s doubling to about a minute.
- The kept-library part (`KeptTagEvidence`) rebuilds only when the library changes; statistics are mapped to the counters affinity reads, so writes of usage time or other counters rebuild nothing.
- Library posts are the profile's own Codices including the Likes collection, whose copies hold each Liked post's every tag. A Like saved before every tag was stored kept only the provider's first ones, alphabetically, so the stored copy's tags are merged with the Like's own; a copy saved later with fewer tags does not drop the Like's tags.
- A post both Liked and in a Codex is one kept post. A search counts once under every source it ran on, so a tag's busiest source is its search count.
- `respellChosen` re-spells saved tags and searches for the source For You fills, since they carry no source.
- `latest` is empty until built, and for a moment after a profile switch holds the last profile's; `forProfile` waits out the switch. Only predictions on kept posts count, so analysing a For You candidate changes nothing.

## Consequences
Stored Like data shapes from earlier releases stay supported.

## Applies to
- `app-logic/src/main/kotlin/com/theoriacodex/app/recommend/TagAffinity.kt`: `tagAffinityInputs`, `KeptTagEvidence`, `respellChosen`, `SharedTagAffinity`, `shareTagAffinityInputs`, `retryDelayMs`
