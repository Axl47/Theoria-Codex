# 0142. Codex collection reads decode covers and payloads once and keep full tag projections off the browsing path

Status: Accepted

## Context

The Library and save surfaces need covers, counts and relevance for every Codex, which can be large. Decoding post payloads per Codex, or scoring on the main thread, would repeat expensive work on each library change.

## Decision

- `CodexCollectionSource` summary queries decode bounded covers only; full tag projections exist only while choosing post actions or a save target. Hold menus preload save relevance, and full membership scoring runs off Main (`Dispatchers.Default`).
- `observeSavedLibrary` decodes payloads once per library change, even for posts saved in several Codices; per-Codex membership comes from ID-only queries.
- The cover thumbnail folder is listed once per update and serves every cover. The thumbnail cache exposes no revision, so the folder is still listed whenever summaries change.
- Named covers are files `<name>_<source id>.<ext>`, and source ids never contain `.`. Built-in sources are always checked; custom sources are found in the listing.

## Consequences

Library updates cost one decode pass regardless of how many Codices share a post. A cover naming scheme that allowed `.` in source ids would break cover lookup.

## Applies to

- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexCollectionSource.kt`: `CodexCollectionSource`, `observeSaveRelevance`, `observeSavedLibrary`
- `feature-library/src/main/kotlin/com/theoriacodex/app/codex/CodexCover.kt`: named-cover lookup (`customSources`)
