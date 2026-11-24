# Architecture decision records

Kotlin and Java sources carry no comments. When code depends on a decision a reader could not work out from
the code itself, such as a platform constraint, a measured tradeoff or a rejected alternative, the decision is
recorded here instead. `scripts/check_comments.py` enforces the ban; the comments that predate it are counted
in `config/quality/comment-baseline.json`, which may only shrink.

## Format

One file per decision, named `NNNN-kebab-case-title.md`. Copy [`template.md`](template.md): context, decision,
consequences, and the code the decision governs, named by file and symbol so a reader can find it. Group
related code under one decision rather than writing one record per call site. Name constants rather than
copying their values, and avoid claims that expire, such as "the latest release". When a decision changes,
edit its record. When it is reversed, or the code it governs is gone, set its status to
`Superseded by [NNNN](NNNN-title.md)` (or `Superseded` when nothing replaces it) and leave the record in place.

Number a new record as the highest number on the latest `main` plus one, and rebase right before pushing. If
another record took the number first, renumber yours before merging. A duplicate that reaches `main` anyway is
harmless: records are identified and linked by file name, so leave both, and the next record still takes the
highest number plus one.

## Finding records

The folder is the index: there is no table to update, so adding a record touches only its own file. Each
record's first line is its number and title, and `grep -h '^# ' docs/adr/0*.md` lists them all. To find the
decisions behind a file, search for its name: `grep -l 'SearchViewModel.kt' docs/adr/*.md`.

## Integrity check

`scripts/check_adrs.py` runs with the other policy checks (`scripts/policy_checks.sh`, the pre-push hook and
CI). It fails when a record's `Applies to` names a path that does not exist, or a backticked symbol that
does not appear in the files on its line. Superseded records are skipped.
Each `Applies to` line reads `` `path`[, `path`]: `symbol`, `symbol` ``; a path without its module directory
resolves against the previous path on the line, a directory stands for every file under it, and prose around the backticked names is ignored. When a
change moves or deletes a file, the check names every record that still points at the old path; update those
records in the same change.
