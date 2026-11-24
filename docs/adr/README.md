# Architecture decision records

Kotlin and Java sources carry no comments. When code depends on a decision a reader could not work out from
the code itself, such as a platform constraint, a measured tradeoff or a rejected alternative, the decision is
recorded here instead. `scripts/check_comments.py` enforces the ban; the comments that predate it are counted
in `config/quality/comment-baseline.json`, which may only shrink.

## Format

One file per decision, named `NNNN-kebab-case-title.md` and numbered in order. Copy
[`template.md`](template.md): context, decision, consequences, and the code the decision governs, named by
file and symbol so a reader can find it. Group related code under one decision rather than writing one
record per call site. When a decision changes, edit its record; when it is reversed, mark it superseded
and link the record that replaces it.

## Index

| ADR | Decision |
| --- | --- |
