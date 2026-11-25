# 0036. Settings are admitted before persistence and change with their derived source weights in one transaction

Status: Accepted

## Context

Settings hold user-owned data, such as recommendation profiles and per-combination source weights. Storage normalization that trims or evicts entries would silently lose it, and weights that drift from their durable memory would disagree between Search and its controls.

## Decision

- `requireSettingsCapacity` rejects settings over the persisted limits before they are written. Storage normalization never evicts user-owned entries.
- `SourceWeightSettings` keeps the active weights and the durable per-combination memory in the same settings transaction. Search and its settings controls read the same combination-specific weights through `weightsFor`.

## Consequences

Callers that exceed a limit get an error instead of losing data. Changing weights always goes through `SourceWeightSettings`, never by writing one half.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SettingsCapacity.kt`: `requireSettingsCapacity`
- `core-data/src/main/kotlin/com/theoriacodex/data/repository/SourceWeightSettings.kt`: `SourceWeightSettings`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/ProfileCapacityTest.kt`: `profile favorite and blacklist overflow preserve the complete durable state (ADR 0036)`, `restore checks combined capacity before mutation and last available slot survives reopen (ADR 0036)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreRepositoriesTest.kt`: `settings persistence policy bounds growing profile-owned collections (ADR 0036)`, `source weight memory survives repository reconstruction (ADR 0036)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/SourceWeightSettingsTest.kt`: `editing active configuration updates active weights too (ADR 0036)`, `group weights survive normalization without changing other source configurations (ADR 0036)`
- `core-data/src/test/kotlin/com/theoriacodex/data/repository/DataStoreRepositoryContractsTest.kt`: `source combinations remember independent weights regardless of source order (ADR 0036)`
- Left to prose: that weights change only through `SourceWeightSettings` is a rule for callers; `SourceRuntimeSettings` is a public data class whose `copy` other modules need for `enabledSources`, and a text rule over `sourceWeights =` would fire on any local of that name.
