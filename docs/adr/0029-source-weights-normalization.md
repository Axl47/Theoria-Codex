# 0029. Source weights are normalized without drift and new sources get an even share

Status: Accepted

## Context

Weights are stored per exact enabled-source combination and re-normalized on every change; custom sites can appear later.

## Decision

- A source with no stored or default weight gets the mean of the other sources' finite weights (1.0 when there are none), not 1.0, which would dominate normalized weights.
- Weights already normalized pass through unchanged so repeated normalization never drifts in the last bit.
- The final normalization divides weights by the largest before summing, so several `Double.MAX_VALUE` inputs cannot overflow that sum. The fallback mean and the already-normalized check still sum raw weights; if the mean overflows, the result falls back to uniform weights.
- Selections stored under an older catalog version gain the sources it introduced once on read.

## Consequences

Changing the tolerance affects stored settings round-trips.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/RepositoryPolicies.kt`: `normalizeSourceWeights` and its `fallbackWeight` handling
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/RepositoryStoreRecords.kt`: `SOURCES_ADDED_BY_CATALOG_VERSION`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/SourceWeightSettingsTest.kt`: `a newly opted-in custom source gets an even share instead of dominating (ADR 0029)`, `weights already normalized pass through bit for bit (ADR 0029)`, `several largest weights normalize to finite shares summing to one (ADR 0029)`, `a missing weight whose mean overflows falls back to even shares (ADR 0029)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreRepositoriesTest.kt`: `minimal pre-catalog settings gain every later source and retain legacy defaults (ADR 0029)`
