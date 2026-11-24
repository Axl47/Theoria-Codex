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

- a test: its file and exact name. The name ends with the record's tag so the failing test names the record at
  the moment someone is tempted to edit it: a backticked Kotlin name ends `` `... (ADR 0022)` ``, and a plain
  identifier (Java, camelCase Kotlin, Python) ends `_adr_0022` (several: `(ADR 0008, 0022)`, `_adr_0008_adr_0022`).
- a signature: its file and the declaration as source text from the start of its line, written on one line
  (line breaks in the code are ignored), such as `` `suspend fun post(id: String): Post?` ``. A pin covers what it
  spells out: to pin an annotation or an initializer, include it.
- a policy rule: two lines, the rule's tagged unit test in `scripts/tests/test_check_architecture.py`, which feeds
  it a violation, and its registered declaration as source text, `` `@rule def rule_name() -> list[str]` ``.
- `Left to prose: <reason>` for promises no machine can check, such as feel or device-only behaviour. Any
  other line must name a backticked path and a guard.

Before linking a guard, break the promise in the code and watch the guard fail. Prefer a host test where one can
pin the promise: `androidTest` guards run only after merge to `main`. A guard in an abstract class runs only
through its concrete subclasses. When a guard fails and the promise still holds, fix the code. When the decision
changed, edit the record and the guard together.

Every live record has a `Guarded by` section, except those listed in `unguarded.txt`, which only shrinks: a new
record needs the section, a record that gains one leaves the list, and on push a name added to it fails. Delete
the file when it is empty.

## Integrity check

`scripts/check_adrs.py` runs with the other policy checks (`scripts/policy_checks.sh`, the pre-push hook and
CI). Superseded records are skipped. It fails when:

- an `Applies to` or `Guarded by` path does not exist, or an `Applies to` symbol does not appear in the files on
  its line. Each line reads `` `path`[, `path`]: `symbol`, `symbol` ``; a path without its module directory
  resolves against the previous path on the line, an `Applies to` directory stands for every file under it,
  prose around the backticked names is ignored, and a line with no backticked path is prose;
- a test guard is not declared exactly once in its file, does not run (`@Test` without `expected` and no
  `@Ignore` on it or its class, or a Python `test_` method of a class with no skip decorator), or lacks the
  record's tag; a test carries a tag its record does not list, or a malformed one;
- a signature guard's text is gone or changed (line breaks and a trailing comma before `)` are ignored; an added
  nullability, type argument, return type or supertype is a change, and so is a modifier or annotation added on
  the pin's own line), a rule or type guard's name is gone, a
  `Guarded by` line names no guard, or the section lists nothing;
- a live record has no `Guarded by` section and is not in `unguarded.txt`, or, on push, the change adds a name
  to that list.

Test files are Kotlin and Java files under source sets named `test`, `androidTest` or `sharedTest`, with any
suffix (`testDebug`, `testFixtures`), and `scripts/tests/test_*.py`. The check proves a guard exists, is tagged
and runs, not that its body still bites; the break-it-first proof and the guard-edit note below cover that.

When a change moves or deletes a file, the check names every record that still points at the old path; update
those records in the same change. On push it also prints notes that never fail: the records whose `Applies to`
symbols the change's edited lines name or fall inside, to reread, and any guard test edited while its record
was not, saying when code the record governs changed too, which usually means the test was bent to pass.
