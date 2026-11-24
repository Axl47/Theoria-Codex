# 0012. The Room database upgrades only through explicit, tested migrations

Status: Accepted

## Context

The library database holds the user's likes, Codex collections and series progress, and has gone through many schema versions. Installed users must reach the latest schema from any earlier one without losing data, and a database that cannot open must be reported rather than silently reset.

## Decision

- `TheoriaRoomDatabase` never opts into destructive fallback. Every schema bump adds a migration and a test for it.
- `openProfileLibrary` opens the database and runs pending migrations before ordinary routes or a developer import admit writes, so a database that cannot open fails at startup, where it is reported.
- The upgrade journey test opens the production factory instead of passing individual migrations to the helper, which proves the whole installed-user path is registered and Room accepts the final schema. It starts from schema 11, the last production release.
- Version 7 added rule groups: existing source-specific rules were OR expressions, so all stay in `group_index` 0.
- Version 14 deleted the extension spike's posts (ids `custom_mihon_` plus digits only) from every table, children before `posts`, and left preferences alone. The `GLOB` pattern must not match real Custom Sites such as `custom_mihon_example_org`; their rows and tag searches containing the prefix stay.
- Backfills pick defaults that match what was known before: picture-hash rows default to tried (every existing row had its hash tried); existing analyses are marked as awaiting frames since they read only a still; `chapters_share_first_page` is off until the Viewer finds it again; `followed_at_epoch_ms` for a series followed earlier uses the earliest first-seen time of its entries, and read times start empty.
- Version 21 dropped the last-checked column, since nothing read it and keeping it would suggest it decides something.

## Consequences

Upgrades are safe but each schema change costs a migration and test. Migrations from retired features stay until old versions are out of the support window.

## Applies to

- `core-data-android/src/main/java/com/theoriacodex/data/android/room/TheoriaRoomDatabase.java`: `MIGRATION_1_2`, `MIGRATION_6_7`, `MIGRATION_13_14`, `SPIKE_SOURCE`, `MIGRATION_15_16`, `MIGRATION_16_17`, `MIGRATION_17_18`, `MIGRATION_18_19`, `MIGRATION_20_21`
- `core-data-android/src/main/kotlin/com/theoriacodex/data/android/room/ProfileLibraryReadiness.kt`: `openProfileLibrary`
- `core-data-android/src/sharedTest/kotlin/com/theoriacodex/data/android/room/RoomUpgradeScenario.kt`: `RoomUpgradeScenario`
