# Architecture decision records

Kotlin and Java sources carry no comments. When code depends on a decision a reader could not work out from
the code itself, such as a platform constraint, a measured tradeoff or a rejected alternative, the decision is
recorded here instead. `scripts/check_comments.py` enforces the ban: any comment in a tracked `.kt` or `.java`
file fails, except the lint and formatter directives and licence notices the script lists. A new exception goes
into `ALLOWED_DIRECTIVE` in that script, matched in full, with a record explaining it.

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

## Guards

A guard is what keeps a record's promise from drifting when someone edits the code without reading the record.
Prefer, in order: a type or signature (narrower visibility, a private constructor, a non-null return, a sealed
type), a focused test whose name states the promise, then a policy rule in `scripts/check_architecture.py` or a
Detekt rule. Each record lists its guards under `Guarded by`, after `Applies to` and in the same grammar:

- a test: its file and exact name. The name ends with the record's tag, `` `... (ADR 0022)` `` in Kotlin or
  `test_..._adr_0022` in Python (several: `(ADR 0008, 0022)`, `_adr_0008_adr_0022`), so the failing test
  names the record at the moment someone is tempted to edit it.
- a signature: its file and the declaration as source text, such as `` `suspend fun post(id: String): Post?` ``.
- a policy rule: its script and function name.
- `Left to prose: <reason>` for promises no machine can check, such as feel or device-only behaviour.

When a guard fails and the promise still holds, fix the code. When the decision changed, edit the record and
the guard together.

## Integrity check

`scripts/check_adrs.py` runs with the other policy checks (`scripts/policy_checks.sh`, the pre-push hook and
CI). Superseded records are skipped. It fails when:

- an `Applies to` or `Guarded by` path does not exist, or an `Applies to` symbol does not appear in the files on
  its line. Each line reads `` `path`[, `path`]: `symbol`, `symbol` ``; a path without its module directory
  resolves against the previous path on the line, an `Applies to` directory stands for every file under it,
  prose around the backticked names is ignored, and a line with no backticked path is prose;
- a test guard is not declared exactly once in its file, does not run (`@Test` without `@Ignore`, or a Python
  `test_` name), or lacks the record's tag; a test anywhere carries a tag its record does not list;
- a signature guard's text is gone (whitespace and a trailing comma before `)` are ignored), a rule or type
  guard's name is gone, or `Guarded by` lists nothing.

When a change moves or deletes a file, the check names every record that still points at the old path; update
those records in the same change. On push it also prints notes that never fail: the records governing the files
the change edits, to reread, and any guard test whose assertions changed beside code its record governs while
the record did not, which usually means the test was bent to pass.
