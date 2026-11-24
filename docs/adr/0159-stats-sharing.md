# 0159. Shared Stats files hold only what was left in, are validated on import, and are compared without being stored

Status: Accepted

## Context

Stats can be shared as a file with another person and compared against theirs. The sender may leave profiles, Codices and posts out, and some profiles may be private; the receiver gets an untrusted file.

## Decision

- The file holds only what the person left in; Codex ids and profile details never enter it. The preview starts with the profile in use because another profile may be private, so including it takes a deliberate tap. The preview shows exactly what the file will hold; the shared file is rebuilt from the current selection, since the preview can trail the last tap.
- Leaving a post out removes its opens and viewing time and the next-ranked posts move up, so the Codex's own post list, time and open totals no longer include them (`shareCodex`); its entry count and browsing time are kept as they are. A post left out of one Codex is left out everywhere it appears. Codices with nothing to compare stay out.
- `StatsShareFile` is written with kotlinx.serialization and held to files older Gson builds wrote by `StatsShareGoldenTest`; `format` and `version` default to null so a file that omits them fails validation instead of defaulting to the current format. Fields added later (`readingMs`, `animeMs`) read as zero when absent. A file's values are bounded (`MAX_STAT_VALUE`, `MAX_CODICES`, `MAX_POSTS_PER_CODEX`), so totals across the cap stay in range and merged posts are capped again as they add up.
- `parseStatsShare` rejects or drops anything malformed so what remains is safe to display; its `@Suppress("ImplicitDefaultLocale")` exists because Detekt mistakes the file's format id (`file.format`) for `String.format`.
- Comparison pairs Codices by name because ids are private to each person's library, and merges a post saved in several Codices once with its activity added together. The other person's numbers (`statsComparison`) live only as long as the comparison screen and are never written to storage or joined to your statistics. A compared post may name a site not set up here; those rows say so instead of failing when tapped.

## Consequences

Exclusions apply to Codices and posts. The overall statistics block has its own toggle (on by default) and is not affected by them. Any new field in the file needs a null default and bounds on import.

## Applies to

- `feature-settings/src/main/kotlin/com/theoriacodex/app/statistics/StatsShare.kt`: `StatsShareFile`, `shareCodex`, `parseStatsShare`, `compareStats`, `mergedPosts`, `MAX_STAT_VALUE`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/StatsSharePreview.kt`: `StatsSharePreview`, `ShareButtons`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/StatsComparisonScreen.kt`: `StatsComparisonScreen`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/settings/SettingsState.kt`: `SettingsUiState.statsComparison`
