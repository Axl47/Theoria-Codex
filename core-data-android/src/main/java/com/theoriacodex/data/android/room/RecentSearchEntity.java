package com.theoriacodex.data.android.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "recent_searches", indices = {
        @Index(value = {"searched_at_epoch_ms", "sort_sequence", "query_hash"})
})
public final class RecentSearchEntity {
    @PrimaryKey @NonNull @ColumnInfo(name = "query_hash") private final String queryHash;
    @NonNull @ColumnInfo(name = "query_payload_json") private final String queryPayloadJson;
    @ColumnInfo(name = "searched_at_epoch_ms") private final long searchedAtEpochMs;
    @ColumnInfo(name = "sort_sequence") private final long sortSequence;

    public RecentSearchEntity(@NonNull String queryHash, @NonNull String queryPayloadJson,
            long searchedAtEpochMs, long sortSequence) {
        this.queryHash = queryHash;
        this.queryPayloadJson = queryPayloadJson;
        this.searchedAtEpochMs = searchedAtEpochMs;
        this.sortSequence = sortSequence;
    }
    @NonNull public String getQueryHash() { return queryHash; }
    @NonNull public String getQueryPayloadJson() { return queryPayloadJson; }
    public long getSearchedAtEpochMs() { return searchedAtEpochMs; }
    public long getSortSequence() { return sortSequence; }
}
