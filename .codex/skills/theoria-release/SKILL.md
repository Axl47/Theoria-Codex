---
name: theoria-release
description: Prepare curated, user-facing Theoria Codex prereleases. Use when drafting a Theoria Codex version bump, writing release notes, validating an Android release version, creating an annotated vX.Y.Z tag, or publishing a tagged Theoria Codex prerelease.
---

# Theoria Codex Release

Prepare exactly one immutable prerelease from an intentional version bump. Keep the user in control of the release wording and of every external Git action.

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
3. Verify all of the following:

   ```sh
   ./gradlew :app:testDebugUnitTest
   ./gradlew :app:assembleRelease
   ```

   Confirm `app/build/outputs/apk/release/output-metadata.json` reports the same name and code. Also confirm the working tree contains only intended release changes and the new version is greater than the prior release.

4. Show the final diff, the exact commit message, and the exact tag that would be created.
5. Create the release-preparation commit only after the user explicitly approves that final diff.

Use this commit message:

```text
chore(release): prepare vX.Y.Z
```

## Publish An Approved Release

Publish only when the user explicitly asks to publish the named version.

1. Confirm `main` is pushed and the target commit is the approved release commit.
2. Create an annotated tag:

   ```sh
   git tag -a --cleanup=verbatim vX.Y.Z -F release-notes/vX.Y.Z.md
   ```

3. Push `main` and that exact tag.
4. Report that GitHub Actions will verify the contract, build/sign the APK, and publish the prerelease.

If any check fails, stop before tagging. Fix the release commit and prepare a newer version rather than altering a published tag.
