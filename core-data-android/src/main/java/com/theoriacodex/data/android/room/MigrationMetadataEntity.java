package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "migration_metadata")
public final class MigrationMetadataEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "migration_key")
    private final String migrationKey;
    @NonNull
    @ColumnInfo(name = "source_fingerprint_sha256")
    private final String sourceFingerprintSha256;
    @NonNull
    @ColumnInfo(name = "codex_file_sha256")
    private final String codexFileSha256;
    @NonNull
    @ColumnInfo(name = "likes_file_sha256")
    private final String likesFileSha256;
    @NonNull
    @ColumnInfo(name = "destination_fingerprint_sha256")
    private final String destinationFingerprintSha256;
    @NonNull
    @ColumnInfo(name = "proof_sha256")
    private final String proofSha256;
    @ColumnInfo(name = "codex_source_present")
    private final boolean codexSourcePresent;
    @ColumnInfo(name = "likes_source_present")
    private final boolean likesSourcePresent;
    @ColumnInfo(name = "codex_count")
    private final int codexCount;
    @ColumnInfo(name = "post_count")
    private final int postCount;
    @ColumnInfo(name = "item_count")
    private final int itemCount;
    @ColumnInfo(name = "like_count")
    private final int likeCount;
    @ColumnInfo(name = "completed_at_epoch_ms")
    private final long completedAtEpochMs;
    @ColumnInfo(name = "codex_archived")
    private final boolean codexArchived;
    @ColumnInfo(name = "likes_archived")
    private final boolean likesArchived;

    public MigrationMetadataEntity(
            @NonNull String migrationKey,
            @NonNull String sourceFingerprintSha256,
            @NonNull String codexFileSha256,
            @NonNull String likesFileSha256,
            @NonNull String destinationFingerprintSha256,
            @NonNull String proofSha256,
            boolean codexSourcePresent,
            boolean likesSourcePresent,
            int codexCount,
            int postCount,
            int itemCount,
            int likeCount,
            long completedAtEpochMs,
            boolean codexArchived,
            boolean likesArchived
    ) {
        this.migrationKey = migrationKey;
        this.sourceFingerprintSha256 = sourceFingerprintSha256;
        this.codexFileSha256 = codexFileSha256;
        this.likesFileSha256 = likesFileSha256;
        this.destinationFingerprintSha256 = destinationFingerprintSha256;
        this.proofSha256 = proofSha256;
        this.codexSourcePresent = codexSourcePresent;
        this.likesSourcePresent = likesSourcePresent;
        this.codexCount = codexCount;
        this.postCount = postCount;
        this.itemCount = itemCount;
        this.likeCount = likeCount;
        this.completedAtEpochMs = completedAtEpochMs;
        this.codexArchived = codexArchived;
        this.likesArchived = likesArchived;
    }

    @NonNull public String getMigrationKey() { return migrationKey; }
    @NonNull public String getSourceFingerprintSha256() { return sourceFingerprintSha256; }
    @NonNull public String getCodexFileSha256() { return codexFileSha256; }
    @NonNull public String getLikesFileSha256() { return likesFileSha256; }
    @NonNull public String getDestinationFingerprintSha256() { return destinationFingerprintSha256; }
    @NonNull public String getProofSha256() { return proofSha256; }
    public boolean isCodexSourcePresent() { return codexSourcePresent; }
    public boolean isLikesSourcePresent() { return likesSourcePresent; }
    public int getCodexCount() { return codexCount; }
    public int getPostCount() { return postCount; }
    public int getItemCount() { return itemCount; }
    public int getLikeCount() { return likeCount; }
    public long getCompletedAtEpochMs() { return completedAtEpochMs; }
    public boolean isCodexArchived() { return codexArchived; }
    public boolean isLikesArchived() { return likesArchived; }
}
