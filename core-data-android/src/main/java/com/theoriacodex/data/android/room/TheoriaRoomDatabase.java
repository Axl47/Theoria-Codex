package com.theoriacodex.data.android.room;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;

@Database(
        entities = {
                CodexEntity.class,
                PostEntity.class,
                CodexItemEntity.class,
                LikedPostEntity.class,
                MigrationMetadataEntity.class
        },
        version = 1,
        exportSchema = true
)
public abstract class TheoriaRoomDatabase extends RoomDatabase {
    public static final String DEFAULT_DATABASE_NAME = "theoria_content.db";

    /**
     * Explicit migrations only. A future schema bump must add and test a migration here; this
     * database intentionally never opts into destructive fallback.
     */
    public static final Migration[] MIGRATIONS = new Migration[0];

    public abstract CodexLikesDao codexLikesDao();

    @NonNull
    public static TheoriaRoomDatabase create(@NonNull Context context) {
        return create(context, DEFAULT_DATABASE_NAME);
    }

    @NonNull
    public static TheoriaRoomDatabase create(
            @NonNull Context context,
            @NonNull String databaseName
    ) {
        return Room.databaseBuilder(
                        context.getApplicationContext(),
                        TheoriaRoomDatabase.class,
                        databaseName
                )
                .addMigrations(MIGRATIONS)
                .build();
    }
}
