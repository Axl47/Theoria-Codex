# 0039. Mihon-family backups are decoded from their protobuf wire format and import their followed or started series

Status: Accepted

## Context

Mihon, TachiyomiJ2K, Anikku and Aniyomi write `.tachibk` backups (gzip around protobuf). Those apps run the same extensions, which name series and entries by the same source number and addresses, so a series can land on the matching extension source and keep its read or watched state.

## Decision

- A series is imported when it has a source number above 0 and the backup follows it or has a last-opened entry: a history entry that matches one of its stored chapters or episodes, even without progress, or else the entry with progress nearest the start. A series from an extension that is missing or disabled here is still imported; the preview reports such sources as missing or to turn on. Extension repos the backup lists come along; categories, trackers, downloads, settings and the apps' local source (source number 0, whose files Theoria cannot read) stay behind.
- Only repos pinned to a signing key and served over https are restored: an address alone would trust whatever key the repo names now. Older Aniyomi backups keep installed extensions in field 106, whose package names are not addresses, so those are not read as repos. A folder address is how these apps store a repo and is taken as is.
- Inflating is capped (`MAX_TACHIYOMI_BACKUP_BYTES`, 64 MB) far above a real library (a 253-series, 40,000-chapter backup inflates to 4 MB) so a hostile file cannot fill memory.
- Positions convert to `StoredSeriesEntry` units: Mihon keeps the last page index from 0, so page 0 is untouched unless TachiyomiJ2K's pages-left field says it was opened; Aniyomi keeps milliseconds though its fields say seconds. The entry opened last is the one history read last, else the furthest with progress, so Continue resumes there.
- Reading mode is the low three bits of the reader flags, as Mihon's `ReadingMode` numbers them: vertical pager, webtoon and continuous vertical read top to bottom; 0 and unknown values follow the app default. The older `viewer` field is still read for older backups.
- Field numbers differ by app: Mihon and J2K keep manga in field 1 with source names in 101 and Mihon repos in 106; Anikku keeps anime in 501 with sources and repos in 503 and 505; Aniyomi keeps anime in field 3. Mihon writes `favorite` (field 100) only when false. The extension memo is JSON text, as bytes (Mihon, Aniyomi) or a string (J2K).
- Tests write backups field by field so the numbers are checked against Mihon's and Anikku's, not Theoria's.

## Consequences

New backup variants need their field numbers added. Aniyomi's field layout has not been checked against a real backup.

## Applies to

- `core-data/src/main/kotlin/com/theoriacodex/data/repository/TachiyomiBackup.kt`: `isTachiyomiBackup`, `decodeTachiyomiBackup`, `gunzip`, `toBackup`, `mihonReadingMode`, `TachiyomiBackup`, `TachiyomiSeries`, `TachiyomiEntry`, `MAX_TACHIYOMI_BACKUP_BYTES`

## Guarded by

- `core-data/src/test/kotlin/com/theoriacodex/data/repository/TachiyomiBackupTest.kt`: `series neither followed nor started, and the local source's, are left behind (ADR 0039)`, `an unfollowed series with progress but no history continues from its furthest entry (ADR 0039)`, `the preview tells a source no extension offers from an extension, or a language, that is off (ADR 0039)`, `manga keep their source, follow and progress, in Theoria's units (ADR 0039)`, `TachiyomiJ2K's pages left marks a chapter opened on its first page and gives its page count (ADR 0039)`, `anime come from Anikku's field with their watched position and duration (ADR 0039)`, `Aniyomi's anime come from its own field (ADR 0039)`, `a manga's reading mode comes from Mihon's reader flags, else the older viewer field (ADR 0039)`, `extension memos come along, and an empty or broken one is none (ADR 0039)`, `importing adds the repos Mihon and Anikku list and the preview names the sources still missing (ADR 0039)`, `a file that is not a Mihon-family backup, or holds no series, is refused (ADR 0039)`, `a corrupt backup reaches the generic message, not the parser's (ADR 0039)`, `only repos pinned to a signing key and served over https come along (ADR 0039)`, `a backup that inflates past 64 MiB is refused as too large, and one at the limit imports (ADR 0039)`
- Left to prose: Aniyomi's field layout is checked against fixtures written from its source, not a real backup; that categories, trackers, downloads and settings stay behind holds because the decoder declares no fields for them.
