# Task Backlog

This backlog records agreed improvements that are not yet scheduled for implementation.

## Product constraints

- Persistent phone-screen space must be reserved for information and controls that remain useful during ordinary repeated use.
- Prefer transient sheets, dialogs, selection modes, and snackbars over permanent explanatory copy or additional rows.
- Optimize for existing users. Do not add onboarding-oriented labels or guidance when stable iconography and placement are sufficient.
- Preserve the current media-forward layout and avoid repeating controls on every post card unless the action is both frequent and important.
- Reuse shared UI ownership when several routes need the same behavior; avoid route-specific copies of feedback, app bars, or filter presentation.

## Backlog

### ENG-001 — Move provider parsing off Gson

Agreed for the release after the saved-data serialization work (PRs #150 to #168) ships, so a provider that breaks can be traced to one change. Saved data, `app` and the Hitomi package already use kotlinx.serialization; `core-sources` still reads every other provider's responses with Gson's tree API in about 25 files, and `core-stubs` uses it too. Background and sizing: `.docs/exec/gson-to-kotlinx-serialization.html` (Surprises, Outcomes) and PR #164, which is the pattern to follow.

- [ ] Port `core-sources` by provider in two or three PRs (about 600 to 900 changed lines): replace the Gson readers in `SourceAdapterCommon.kt` with the lenient kotlinx readers in `common/SourceJson.kt`, then move each provider onto them.
- [ ] Before porting a provider, add tests that pin what its parser tolerates today (numbers as strings, wrong types, missing keys, `null` lists), as PR #164 did, so they pass unchanged afterwards.
- [ ] Check whether the scraped sources (nhentai mirrors, custom-site probes, Rule34 JSON embedded in HTML) rely on Gson accepting input that is not strict JSON, and keep that tolerance where they do.
- [ ] Port sign-in and account parsing for Pixiv, Iwara and Danbooru last and on its own, since it reads credentials.
- [ ] Prove each provider against its live service before the release, not only with host tests.
- [ ] Port `core-stubs`, then remove Gson from `core-sources/build.gradle.kts`, `core-stubs/build.gradle.kts` and the version catalog, and regenerate the baseline profiles.
