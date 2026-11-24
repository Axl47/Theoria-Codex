# Architecture decision records

Kotlin and Java sources carry no comments. When code depends on a decision a reader could not work out from
the code itself, such as a platform constraint, a measured tradeoff or a rejected alternative, the decision is
recorded here instead. `scripts/check_comments.py` enforces the ban; the comments that predate it are counted
in `config/quality/comment-baseline.json`, which may only shrink.

## Format

One file per decision, named `NNNN-kebab-case-title.md` and numbered in order. Copy
[`template.md`](template.md): context, decision, consequences, and the code the decision governs, named by
file and symbol so a reader can find it. Group related code under one decision rather than writing one
record per call site. Name constants rather than copying their values, and avoid claims that expire, such
as "the latest release". When a decision changes, edit its record; when it is reversed, mark it superseded
and link the record that replaces it.

## Index

| ADR | Decision |
| --- | --- |
| [0001](0001-comment-links.md) | Comment links use the site's anchor when it has one, else a fragment browsers ignore |
| [0002](0002-coroutine-and-diagnostics-rules.md) | Shared requests, cancellation, diagnostics and off-main checks follow fixed rules |
| [0003](0003-cross-source-tags.md) | For You trains across sources by translating tags, with foreign likes capped |
| [0004](0004-custom-site-profiles.md) | Custom booru sites get ids compatible with existing encodings, validated HTTPS roots and secret-safe credentials |
| [0005](0005-picture-tagger-thresholds.md) | Predicted picture tags are a separate tag source with per-use score thresholds |
| [0006](0006-post-identity-and-dedup.md) | One file or picture shown once: posts are deduplicated by ID, MD5 and a banded perceptual hash |
| [0007](0007-series-sources-and-extensions.md) | Series come from Mihon and Aniyomi extensions installed into app storage, trusted by pinned signing key |
| [0008](0008-source-adapter-contracts.md) | Source capabilities are optional adapter interfaces with throw-on-failure and null-for-absent results |
| [0009](0009-stored-data-compatibility.md) | Persisted identifiers, hashes and legacy fields keep their original form |
| [0010](0010-tag-and-text-normalization.md) | Tag spelling is normalized per source dialect and provider quirks are undone at the edge |
| [0011](0011-unified-search-orchestration.md) | Unified search publishes early, merges stably and shapes requests per source |
| [0012](0012-room-explicit-migrations.md) | The Room database upgrades only through explicit, tested migrations |
| [0013](0013-room-read-paths-and-projections.md) | Room reads avoid transactions and reuse decoded posts |
| [0014](0014-series-backup-merge.md) | Restoring a series backup only adds to what the device holds |
| [0015](0015-series-entry-storage.md) | Series entries are kept, never deleted, when a source stops listing them |
| [0016](0016-shared-post-storage.md) | Posts are stored once, sparsely and compared by decoded value |
| [0017](0017-visual-similarity-storage.md) | Visual signatures and For You verdicts are stored with their own lifecycle |
| [0018](0018-host-test-compose-waits.md) | Host-test Compose waits drain the main looper |
