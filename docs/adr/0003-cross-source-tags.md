# 0003. For You trains across sources by translating tags, with foreign likes capped

Status: Accepted

## Context

Likes on one source should teach recommendations on tag-compatible sources, without foreign likes overwhelming a user's own history. Recommendation affinity has different compatibility needs than search matching and favorite-tag persistence.

## Decision

- `crossSourceTagKey` follows Danbooru's own Pixiv-tag normalization (NFKC, lowercase, whitespace and underscore runs to one space).
- `CrossSourceTraining` trains a target on its own likes first, then on translated likes from other sources; unmappable tags are dropped, and foreign documents are capped at `max(native, FOREIGN_DOCUMENT_FLOOR)` so they bridge a cold start but never outweigh the target's own likes. Likes should be ordered most recent first; each document is translated at most once.
- `sharesBooruVocabulary` includes custom sites by default, betting on Danbooru-style tags. Nothing persists it, so it can change or become per-site without migration.
- `RecommendationTagNormalization` is separate from search matching and persisted favorite tags, which have different compatibility requirements.

## Consequences

Changing recommendation normalization does not touch stored favorites or search. Persisting the vocabulary rule would require a migration.

## Applies to

- `core-domain/src/main/kotlin/com/theoriacodex/domain/recommendation/CrossSourceTags.kt`: `crossSourceTagKey`, `CrossSourceTraining`, `sharesBooruVocabulary`
- `core-domain/src/main/kotlin/com/theoriacodex/domain/recommendation/RecommendationTagNormalization.kt`: `RecommendationTagNormalization`
