---
name: theoria-release
description: Publish a curated Theoria Codex prerelease from the latest main. Use when the user asks to release, ship, or make an update of Theoria Codex, or to draft a version bump or release notes.
---

# Theoria Codex Release

Publish exactly one prerelease from the newest `origin/main`. The only human step is approving the version and notes. That approval authorizes everything below it, through publication, so do not ask again.

## Release Contract

Treat these as one release unit:

- `app/build.gradle.kts` declares `versionName = "X.Y.Z"`.
- `versionCode` equals `1_500_000_000 + major * 10_000 + minor * 100 + patch`.
- `release-notes/vX.Y.Z.md` is the GitHub prerelease body and the in-app changelog.
- The tag `vX.Y.Z` is created by the Release workflow, and only after every check has passed.

Never use a low sequential Android code: the updater compares against the high SemVer-derived range. Never move, delete, or reuse a published release tag.

## Versioning Policy

Starting with 1.0.0, choose the version based on the most significant user-facing change in the release:

- **Major:** A fundamental product change or deliberate breaking change to a supported user-facing contract that meaningfully changes what users should expect.
- **Minor:** New capabilities or meaningful expansions and redesigns of existing flows.
- **Patch:** Fixes, performance and reliability improvements, and small refinements that do not substantially change what the app can do.

Judge by user impact, not implementation size: an internal rewrite or data-schema migration can be a patch when behavior remains the same and existing data carries forward. A major release does not permit losing saved data or credentials; preserve migrations and clearly communicate any unavoidable incompatibility.

Keep minor and patch components below 100 so the Android version-code formula remains collision-free.

## Release-Note Writing Convention

- Start with `## Highlights`: 2–4 short bullets summarizing the update's main user-visible outcomes and benefits. Prefer plain language over feature inventories or implementation detail. For a small patch, one useful highlight is enough.
- Follow with `## New`, `## Improvements`, `## Fixes`, and `## Known Issues` only when relevant. These sections explain the specific behaviors, options, limitations, and fixes behind the summary; do not repeat highlight sentences verbatim.
- Highlights is the concise overview, not an exhaustive list. Smaller changes may appear only in the detailed sections. Keep known issues explicit in their own section.
- Use the exact heading `Highlights` so the app presents it as the prominent summary card. The app initially collapses the other sections under “Full details” when Highlights is present. Notes without Highlights remain readable as ordinary sections.
- Apply this convention to new release notes; preserve published release wording.

## 1. Start From The Latest Main

Run `git fetch origin --tags --prune`, then compare the checkout with `origin/main`.

- If the checkout has uncommitted work, or commits that are not on `origin/main`, stop and tell the user: **"Those changes aren't on main yet. Make a PR first."** A release only ever ships what is on `origin/main`.
- If the checkout is merely behind, update it with `git switch main && git pull --ff-only`.

## 2. Draft And Get Approval

Change nothing yet.

1. Find the most recent `vX.Y.Z` tag, read the `[Unreleased]` section of root `CHANGELOG.md`, and review user-visible changes since the tag. Treat pending notes as drafting input, not a substitute for reviewing the changes.
2. Read relevant feature behavior, not only Conventional Commit subjects. Exclude internal refactors, test-only work, and implementation detail unless users notice its effect.
3. Propose the next version and write the notes.
4. Present them and ask for approval. This is the only question in the whole flow.

## 3. Publish After Approval

1. Update `versionName` and `versionCode` in `app/build.gradle.kts`, and add `release-notes/vX.Y.Z.md` with the approved text.
2. Move the released entries from `[Unreleased]` into a dated `[X.Y.Z]` section directly below the now-empty `[Unreleased]` section of root `CHANGELOG.md`.
3. If startup or the main journeys changed since the last tag and a supported API 36 or 37 emulator is free, refresh the baseline profile with `ANDROID_SERIAL=<emulator-serial> ./gradlew :app:generateReleaseBaselineProfile`. Otherwise skip it and say so.
4. Run `python3 scripts/release_gate.py`. Fix any problem it reports.
5. Commit only those release files as `chore(release): prepare vX.Y.Z` and push the commit straight to `main`. A release commit needs no PR.
6. Run `gh workflow run release.yml --ref main`, find the new run, and confirm its `headSha` is that commit.
7. Monitor the run to completion. Every push to `main` is already proven by the Main workflow: the pre-PR check, the R8 release builds, and emulator tests on Android 16 and 17, each recorded when it passes. Release accepts those results (a release-only commit reuses its parent's) and just signs, builds, and publishes, in about 6 minutes. Any result still missing, for example because Main is still running, runs inside the Release itself, so expect about 15 minutes then. It creates the tag and publishes only if all of it passes.
8. Confirm the prerelease exists, is not a draft, uses the expected tag and notes, and contains `theoria-codex-main.apk`.

## When The Run Fails

No tag exists until the run succeeds, so a failure never uses up the version.

- **Emulator or runner trouble** (emulator offline or failing to boot, a failed SDK download): run `gh run rerun <run-id> --failed` once. Jobs that passed are kept.
- **A real failure** (a failing test, or a release acceptance error): fix it through a PR, then dispatch Release again on the new `main` with the same version and notes. The fix's Main run proves it; dispatching before that finishes is fine, the missing pieces just run inside the Release.
