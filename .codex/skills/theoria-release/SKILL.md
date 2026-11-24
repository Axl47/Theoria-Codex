---
name: theoria-release
description: Publish a curated Theoria Codex prerelease from the latest main. Use when the user asks to release, ship, or make an update of Theoria Codex, or to draft a version bump or release notes.
---

# Theoria Codex Release

Publish exactly one prerelease from the newest `origin/main`. You draft the version and notes and get them approved; that is the only human step. Then the `theoria-release` T3 Code workflow ([theoria-release.ts](theoria-release.ts)) carries the release to the end, and you relay how it ended. Approval authorizes everything after it, through publication, so do not ask again.

## Release Contract

Treat these as one release unit:

- `app/build.gradle.kts` declares `versionName = "X.Y.Z"`.
- `versionCode` equals `1_500_000_000 + major * 10_000 + minor * 100 + patch`.
- `release-notes/vX.Y.Z.md` is the GitHub prerelease body and the in-app changelog.
- The tag `vX.Y.Z` is created by the Release workflow, and only after every check has passed.

Never use a low sequential Android code: the updater compares against the high SemVer-derived range. Never move, delete, or reuse a published release tag.

Only `vX.Y.Z` tags are app releases. Other tags, such as `models-vision-N` (model files the app downloads on demand), are not: never use them as the previous release.

## Who Does What

Keep the version choice and the release notes on the main model. When there are too many changes since the tag to read yourself, give Haiku subagents a slice of the commits each and have them return the user-visible changes they found, with SHAs. The workflow makes the release commit, runs Release, reruns runner trouble once, reads failed logs with Haiku, and checks the published prerelease.

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

1. If `origin/main` already declares a version that has `release-notes/vX.Y.Z.md` but no `vX.Y.Z` tag, an approved release failed and its fix has since landed. Skip drafting and go to step 3 with that version and those notes.
2. Record the `origin/main` SHA you draft against. The workflow refuses to release if main moves after it.
3. Find the most recent `vX.Y.Z` tag, read the `[Unreleased]` section of root `CHANGELOG.md`, and review user-visible changes since the tag. Treat pending notes as drafting input, not a substitute for reviewing the changes.
4. Read relevant feature behavior, not only Conventional Commit subjects. Exclude internal refactors, test-only work, and implementation detail unless users notice its effect.
5. Propose the next version and write the notes. If startup or the main journeys changed since the tag, say the baseline profile may be stale; refreshing it (`./gradlew :app:generateReleaseBaselineProfile` on an API 36 or 37 emulator) goes through a PR before the release, not in it.
6. Present them and ask for approval. This is the only question in the whole flow.

## 3. Start The Workflow

Write the approved notes to a temporary file, then from the checkout:

```sh
input=$(jq -nc --arg repo "$(git rev-parse --show-toplevel)" --arg base "<drafted SHA>" \
  --arg version "X.Y.Z" --rawfile notes <notes file> \
  '{repo: $repo, base: $base, version: $version, notes: $notes}')
t3 workflow start theoria-release --key "vX.Y.Z/$(date +%s)" --input "$input" --json
```

- If T3 says workflows are off, ask the user to turn on Settings → Labs → Workflows. If it doesn't know `theoria-release`, link `~/.t3/workflows/theoria-release.ts` to the main checkout's copy of this skill's `theoria-release.ts` and start again.
- Add `dryRun: true` to rehearse: the run makes and gates the release commit, returns its diff, and pushes nothing.

The workflow writes the release files with `scripts/release_gate.py prepare` (`versionName`, `versionCode`, `release-notes/vX.Y.Z.md`, and `[Unreleased]` moved under a dated `[X.Y.Z]` heading in `CHANGELOG.md`), runs the release gate, and pushes `chore(release): prepare vX.Y.Z` straight to `main` from a worktree of its own, so your checkout stays as it is. It then dispatches Release with `expected-sha` set to that commit, so a PR merged in between can't ship in it. Release signs, builds, and publishes in about 6 minutes, or about 15 when a Main result is still missing and runs inside it. Last, it checks that the prerelease is not a draft, has the tag on the release commit, has the approved notes, and has `theoria-codex-main.apk`.

Wait until `t3 workflow show <run> --json` reports `.run.status` as `completed`, `stopped`, `cancelled`, or `paused`: poll it from a background command and wait on that with the tool built for it. Then report the release URL, or the reason the run gives.

## When The Run Doesn't Complete

No tag exists until Release succeeds, so a failure never uses up the version.

- **Stopped:** the reason says what to do. Runner or emulator trouble was already rerun once; another failure there can be retried by starting the workflow again. A real failure (a failing test, a release acceptance error) needs a fix through a PR; once it is on `main`, run this skill again, and step 2.1 picks the release back up. If main moved before the release commit, draft again.
- **Paused:** a step hit an error, such as GitHub being unreachable, and retries on its own after 5, 20, and 80 minutes. Read the failing step with `t3 workflow show <run>`, fix the cause, and `t3 workflow resume <run>`.

## Without T3 Workflows

If the workflow can't run, do its steps by hand from a clean checkout of `origin/main`: `python3 scripts/release_gate.py prepare X.Y.Z <notes file>`, `python3 scripts/release_gate.py`, commit the three files as `chore(release): prepare vX.Y.Z`, push to `main`, run `gh workflow run release.yml --ref main -f expected-sha=<that commit>`, watch the run, rerun runner trouble once with `gh run rerun <run-id> --failed`, and check the prerelease as above.
