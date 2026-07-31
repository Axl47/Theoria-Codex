package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "recents_migration_metadata")
public final class RecentsMigrationEntity {
    @PrimaryKey @NonNull @ColumnInfo(name = "migration_key") private final String migrationKey;
    @NonNull @ColumnInfo(name = "source_sha256") private final String sourceSha256;
    @ColumnInfo(name = "source_byte_count") private final long sourceByteCount;
    @ColumnInfo(name = "source_present") private final boolean sourcePresent;
    @ColumnInfo(name = "source_quarantined") private final boolean sourceQuarantined;
    @NonNull @ColumnInfo(name = "destination_sha256") private final String destinationSha256;
    @ColumnInfo(name = "watched_count") private final int watchedCount;
    @ColumnInfo(name = "search_count") private final int searchCount;
    @NonNull @ColumnInfo(name = "proof_sha256") private final String proofSha256;
    @ColumnInfo(name = "completed_at_epoch_ms") private final long completedAtEpochMs;
    @ColumnInfo(name = "source_archived") private final boolean sourceArchived;

    public RecentsMigrationEntity(@NonNull String migrationKey, @NonNull String sourceSha256,
            long sourceByteCount, boolean sourcePresent, boolean sourceQuarantined,
            @NonNull String destinationSha256, int watchedCount, int searchCount,
            @NonNull String proofSha256, long completedAtEpochMs, boolean sourceArchived) {
        this.migrationKey = migrationKey; this.sourceSha256 = sourceSha256;
        this.sourceByteCount = sourceByteCount; this.sourcePresent = sourcePresent;
        this.sourceQuarantined = sourceQuarantined; this.destinationSha256 = destinationSha256;
        this.watchedCount = watchedCount; this.searchCount = searchCount; this.proofSha256 = proofSha256;
        this.completedAtEpochMs = completedAtEpochMs; this.sourceArchived = sourceArchived;
    }
    @NonNull public String getMigrationKey() { return migrationKey; }
    @NonNull public String getSourceSha256() { return sourceSha256; }
    public long getSourceByteCount() { return sourceByteCount; }
    public boolean isSourcePresent() { return sourcePresent; }
    public boolean isSourceQuarantined() { return sourceQuarantined; }
    @NonNull public String getDestinationSha256() { return destinationSha256; }
    public int getWatchedCount() { return watchedCount; }
    public int getSearchCount() { return searchCount; }
    @NonNull public String getProofSha256() { return proofSha256; }
    public long getCompletedAtEpochMs() { return completedAtEpochMs; }
    public boolean isSourceArchived() { return sourceArchived; }
}
