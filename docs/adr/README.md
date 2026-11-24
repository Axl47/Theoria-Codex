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
| [0018](0018-codex-library-transactions.md) | Cross-Codex and Likes operations commit atomically and Undo authority is process-only |
| [0019](0019-followed-and-saved-new-post-marks.md) | Follows and new-post marks are local, identity-keyed and rate-limited across restarts |
| [0020](0020-for-you-retired-searches.md) | Saved For You searches are converted to ordinary source searches without displacing existing ones |
| [0021](0021-local-state-files.md) | Small local state files reject newer schemas, skip unreadable entries and keep lists as raw JSON |
| [0022](0022-offline-media-store.md) | Offline copies commit through a manifest and survive undecodable data without losing owners |
| [0023](0023-post-snapshots.md) | Post snapshots drop device-bound data and merging never erases known data |
| [0024](0024-profile-backup-format.md) | Profile backups are a versioned, strictly validated document written at the lowest version that fits |
| [0025](0025-profile-backup-restore.md) | Restore merges through a durable intent and only adds to what the device has |
| [0026](0026-profile-deletion-journal.md) | Profile deletion runs from a fail-closed durable intent and blocks writes to retired profiles |
| [0027](0027-series-download-queue.md) | Chapter downloads run from a two-lane queue with one worker |
| [0028](0028-series-storage-and-switching.md) | Series entry lists, progress and source switches are stored conservatively |
| [0029](0029-source-weights-normalization.md) | Source weights are normalized without drift and new sources get an even share |
| [0030](0030-statistics-recording.md) | Usage statistics split by local calendar day, merge by maximum and seed tag counts once |
| [0031](0031-custom-site-profile-storage.md) | User-added site profiles are stored without credentials, skip invalid records, and merge on restore without the site limit |
| [0032](0032-datastore-open-and-recovery.md) | Stores open through a startup gate, refuse newer schemas, and set unreadable files aside before resetting |
| [0033](0033-durable-json-configuration.md) | All saved data uses one kotlinx Json configuration that keeps Gson-era behaviour |
| [0034](0034-golden-files-pin-stored-formats.md) | Golden files are bytes real builds wrote and are never regenerated to pass a change |
| [0035](0035-repository-state-cells.md) | Repository rules run over a StateCell so in-memory and durable backings share one code path |
| [0036](0036-settings-persistence-rules.md) | Settings are admitted before persistence and change with their derived source weights in one transaction |
| [0037](0037-statistics-thumbnail-store.md) | Statistics thumbnails are private files owned by the top lists and fetched only for promoted or opened posts |
| [0038](0038-stored-record-compatibility.md) | Durable records are separate from domain models and keep decoding every shape this app ever stored |
| [0039](0039-tachiyomi-backup-import.md) | Mihon-family backups are decoded from their protobuf wire format and import their followed or started series |
| [0040](0040-startup-offline-catalog-and-full-display.md) | Startup does not wait for the offline catalog, and the launch reports full display from the restored tab |
