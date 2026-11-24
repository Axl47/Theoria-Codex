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
