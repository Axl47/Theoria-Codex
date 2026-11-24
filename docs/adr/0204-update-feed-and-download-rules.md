# 0204. The updater tolerates malformed release entries and publishes a download only from a still-admitted caller

Status: Accepted

## Context

The updater is how installs receive fixes, so one bad entry in GitHub's list-releases response must not hide a good release. Downloads run as blocking reads that can outlive their caller when cancelled.

## Decision

- `GitHubReleaseFeedClient` decodes the response one release at a time and each release's `assets` one at a time (`List<JsonElement>`), so a malformed asset is skipped instead of hiding its release, and a malformed release does not hide the others. `GitHubRelease` declares only the fields the updater reads.
- `ApkDownloadManager.downloadInside` writes each download to its own unique staging file (`update-*.part`) and moves it to the final path with an atomic move only after `ensureActive`, so only the still-admitted caller may publish; a late blocking read from a cancelled caller writes only its own staging file. Leftover staging files are cleaned once per manager.

## Consequences

A release whose assets are all malformed is skipped, not an error. Two overlapping downloads cannot corrupt the published APK.

## Applies to

- `app/src/main/java/com/theoriacodex/app/update/GitHubReleaseFeedClient.kt`: `GitHubRelease`, `GitHubReleaseAsset`
- `app/src/main/java/com/theoriacodex/app/update/ApkDownloadManager.kt`: `downloadInside`
