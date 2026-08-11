---
name: theoria-release
description: Prepare curated, user-facing Theoria Codex prereleases. Use when drafting a Theoria Codex version bump, writing release notes, validating an Android release version, creating an annotated vX.Y.Z tag, or publishing a tagged Theoria Codex prerelease.
---

# Theoria Codex Release

Prepare exactly one immutable prerelease from an intentional version bump. Keep the user in control of the release wording and of every external Git action. Treat the pushed release commit's GitHub checks as the authority that unlocks tagging.

## Release Contract

Treat these files and identifiers as one release unit:

- `app/build.gradle.kts` declares `versionName = "X.Y.Z"`.
- `versionCode` equals `1_500_000_000 + major * 10_000 + minor * 100 + patch`.
- `release-notes/vX.Y.Z.md` contains the user-facing GitHub prerelease body and in-app changelog.
- The annotated Git tag is `vX.Y.Z`, and its annotation exactly matches `release-notes/vX.Y.Z.md`.

Never use a low sequential Android code. Existing releases and the updater compare against the high SemVer-derived range. Never move, delete, reuse, or force-push a release tag.

## Draft A Release By Default

Start without changing files or Git state.

1. Confirm the checkout is clean and based on `main`. Fetch tags.
2. Identify the most recent `vX.Y.Z` release tag and review user-visible changes since it.
3. Read relevant feature behavior, not only Conventional Commit subjects. Exclude internal refactors, test-only work, and implementation detail unless users notice its effect.
4. Propose the next version, calculate its Android code, and write concise notes grouped under `Highlights`, `New`, `Improvements`, `Fixes`, and, when relevant, `Known Issues`.
5. Present the proposal in the response and ask for approval.

Do not edit files, create a commit, create a tag, push, or publish during this draft phase.

## Prepare An Approved Release

Proceed only after the user approves the version and release-note wording.

1. Update `versionName` and its matching calculated `versionCode` in `app/build.gradle.kts`.
2. Add `release-notes/vX.Y.Z.md` using the approved user-facing text.
3. Run the host validation batch:

   ```sh
   previous_release_tag="$(git describe --tags --abbrev=0 --match 'v[0-9]*')"
   python3 scripts/check_hotspots.py --base "$previous_release_tag"
   ./gradlew :app:detektDebug --stacktrace
   ./gradlew :app:compileDebugAndroidTestKotlin --stacktrace
   ./gradlew :app:testDebugUnitTest
   ./gradlew \
     :app:assembleRelease \
     :app:verifyReleaseJsonContracts \
     :app:verifyReleaseAcceptanceJsonContracts \
     --stacktrace
   ```

   If a supported API 35 target is already available and the packaged application IDs and signing lanes have been proven safe, also run `./gradlew :app:connectedDebugAndroidTest --stacktrace`. Otherwise report the local connected lane as unavailable; do not substitute another API level or personal device. The pushed commit's GitHub `Device Validation` workflow must pass before tagging, so local unit/build success never replaces instrumentation coverage.

   Confirm the hotspot/Detekt debt gate and Detekt report no new issues before continuing.
   Confirm `app/build/outputs/apk/release/output-metadata.json` reports the same name and code. Also confirm the working tree contains only intended release changes and the new version is greater than the prior release.

4. Show the final diff, the exact commit message, and the exact tag that would be created.
5. Create the release-preparation commit only after the user explicitly approves that final diff.

Use this commit message:

```text
chore(release): prepare vX.Y.Z
```

6. After approval, commit and push `main`, then record the full release commit SHA. Do not create the release tag yet.
7. Monitor every GitHub Actions workflow triggered for that exact SHA, including `Verify`, `Device Validation`, and `Dependency Submission`. Match by full `headSha`; never use an older green run as evidence.
8. Wait until every triggered workflow completes successfully. If any workflow fails, is cancelled, or cannot run, stop before tagging, inspect the failing check, and prepare a new release commit after the repair. Do not treat host success as permission to bypass this gate.

## Publish An Approved Release

Publish only when the user explicitly asks to publish the named version.

1. Confirm all GitHub Actions workflows for the exact approved release commit completed successfully. If they are still running, keep monitoring. If any did not succeed, stop before tagging.
2. Refetch `main` and tags. Confirm the checkout is clean, `HEAD` equals `origin/main`, the target commit is the approved release commit, and the version tag does not exist locally or remotely.
3. Create an annotated tag whose message is the checked-in, approved changelog:

   ```sh
   git tag -a --cleanup=verbatim vX.Y.Z -F release-notes/vX.Y.Z.md
   ```

4. Before pushing, verify that the tag points to the approved release commit and that its annotation exactly matches `release-notes/vX.Y.Z.md`:

   ```sh
   test "$(git rev-list -n 1 vX.Y.Z)" = "$(git rev-parse HEAD)"
   test "$(git tag -l --format='%(contents)' vX.Y.Z)" = "$(cat release-notes/vX.Y.Z.md)"
   ```

5. Push only that exact tag; `main` must already be synchronized from the preparation phase.
6. Monitor the tag-triggered `Main Prerelease` workflow to completion. If it fails, report the immutable release failure and prepare a newer patch version for any repair; never move or recreate the tag.
7. Confirm the GitHub prerelease exists, is not a draft, uses the expected tag and notes, and contains the uploaded `theoria-codex-main.apk` asset before reporting publication complete.

If any check fails, stop before tagging. Fix the release commit and prepare a newer version rather than altering a published tag.
