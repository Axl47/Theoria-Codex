# 0155. Daily backup runs when the app leaves the foreground and never deletes files it did not name

Status: Accepted

## Context

Library data changes only while the app is in use, so no background scheduling is needed. The folder grant is specific to one install, and a backup write into a user-chosen folder can be interrupted or the folder swapped mid-run.

## Decision

- `AutoBackupWorkflow.onAppBackgrounded` runs the backup when it is due; the interval is 20 hours (`AUTO_BACKUP_INTERVAL_MS`), shorter than a day so a habit of opening the app at about the same time still backs up daily.
- The schedule state (`AutoBackupStore`) is device-local: a folder grant cannot travel with a backup or to another install. A write to the folder (`AutoBackupFolder`) leaves either a complete, verified file or none at all.
- Turning daily backup off or changing folders during a run must not revive the old state: a run's result is dropped when the folder has changed (see below).
- A write goes to a `.part` document, is read back and compared with the bytes written, and is only then renamed to its final name; on any failure the `.part` is deleted (best effort), so an interrupted run can leave a `.part` behind but never a partial backup under the real name.
- The run applies its result only when the stored folder URI equals the one it started with; a different folder is left untouched. Switching away and back to the same URI during a run cannot be told apart, so that run's result is accepted.
- Pruning deletes only complete files named by this feature (`AUTO_BACKUP_NAME`), keeps `AUTO_BACKUP_KEEP_COUNT`, and runs only after the new file was written; leftover `.part` files of this feature are removed first. If the write fails, pruning does not run.
- Backup documents are read through `BackupDocuments`: the owner passes document IDs while stream and permission lifetimes stay in the gateway; `readBackupDocument` reads at most one extra byte beyond the limit, including for streams without a size. `BackupViewModel` is activity-owned so jobs and selected bytes survive UI recreation without reopening the document.
- The restore preview is the one backup step that asks first, because restoring adds to every matching profile.

## Consequences

A user who never backgrounds the app never gets an automatic backup. Changing the file naming pattern would orphan existing files from pruning.

## Applies to

- `feature-settings/src/main/kotlin/com/theoriacodex/app/backup/AutoBackup.kt`: `AutoBackupWorkflow`, `AutoBackupStore`, `AutoBackupFolder`, `AUTO_BACKUP_INTERVAL_MS`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/backup/BackupDocuments.kt`: `BackupDocuments`, `readBackupDocument`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/backup/BackupViewModel.kt`: `BackupViewModel`
- `feature-settings/src/main/kotlin/com/theoriacodex/app/backup/BackupControls.kt`: `BackupDialogs`
