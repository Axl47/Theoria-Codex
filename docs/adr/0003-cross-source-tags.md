# 0003. For You trains across sources by translating tags, with foreign likes capped

Status: Accepted

## Context

Likes on one source should teach recommendations on tag-compatible sources, without foreign likes overwhelming a user's own history. Recommendation affinity has different compatibility needs than search matching and favorite-tag persistence.

## Decision

- `crossSourceTagKey` follows Danbooru's own Pixiv-tag normalization (NFKC, lowercase, whitespace and underscore runs to one space).
- `CrossSourceTraining` trains a target on its own likes first, then on translated likes from other sources; unmappable tags are dropped, and foreign documents are capped at `max(native, FOREIGN_DOCUMENT_FLOOR)` (24), so with few or no native likes up to 24 foreign documents still bridge a cold start, and otherwise they never outnumber the target's own likes. Likes should be ordered most recent first. Each document's concept keys (the normalized, source-independent form of its tags) are computed once and cached; they are spelled again for every target, since the target's own spelling differs (underscores, or a Pixiv native tag from the dictionary).
- `sharesBooruVocabulary` includes custom sites by default, betting on Danbooru-style tags. Nothing persists it, so it can change or become per-site without migration.
- `RecommendationTagNormalization` is separate from search matching and persisted favorite tags, which have different compatibility requirements.

## Consequences

Changing recommendation normalization does not touch stored favorites or search. Persisting the vocabulary rule would require a migration.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/recommendation/CrossSourceTags.kt`: `crossSourceTagKey`, `CrossSourceTraining`, `sharesBooruVocabulary`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/recommendation/RecommendationTagNormalization.kt`: `RecommendationTagNormalization`

## Guarded by

- `core-domain/src/test/kotlin/com/theoriacodex/domain/recommendation/CrossSourceTagsTest.kt`: `concept keys ignore booru spelling, width and escaping (ADR 0003)`, `pixiv likes teach booru sources through translations (ADR 0003)`, `foreign documents never outnumber native ones beyond the cold-start floor (ADR 0003)`, `custom sites share the booru vocabulary in both directions (ADR 0003)`
- `scripts/tests/test_check_architecture.py`: `test_reports_recommendation_normalization_outside_its_named_callers_adr_0003`
- Left to prose: ordering likes most recent first is the caller's precondition, caching concept keys changes no result, and that nothing persists `sharesBooruVocabulary` is an absence no check can point at.
