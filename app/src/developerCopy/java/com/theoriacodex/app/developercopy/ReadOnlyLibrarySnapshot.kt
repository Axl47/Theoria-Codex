package com.theoriacodex.app.developercopy

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Stage rows from a version-stable read-only connection, including committed WAL rows. Never copy
 * live DB files, checkpoint production, or open production through Room's writable helper.
 * The disposable destination lets the existing Room backup mapper own the logical conversion.
 */
internal suspend fun stageReadOnlyLibrary(sourceFile: File, destination: File) {
    require(sourceFile.isFile && !destination.exists())
    withUnchangedReadOnlyLibrary(sourceFile) { source ->
        SQLiteDatabase.openOrCreateDatabase(destination, null).use { staged ->
            val schema = readSchema(source)
            staged.beginTransaction()
            try {
                schema.filter { it.type == "table" }.forEach { table ->
                    staged.execSQL(table.sql)
                    if (table.name in libraryTables) copyRows(source, staged, table.name)
                }
                schema.filter { it.type == "index" }.forEach { staged.execSQL(it.sql) }
                staged.version = source.version
                staged.setTransactionSuccessful()
            } finally {
                staged.endTransaction()
            }
        }
    }
}

/** API 26+ needs no writable BEGIN: reject any external commit, including changes between cursor windows. */
internal suspend fun <T> withUnchangedReadOnlyLibrary(file: File, read: suspend (SQLiteDatabase) -> T): T =
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { source ->
        require(source.isReadOnly)
        val before = source.compileStatement("PRAGMA data_version").use { it.simpleQueryForLong() }
        val result = read(source)
        val after = source.compileStatement("PRAGMA data_version").use { it.simpleQueryForLong() }
        require(before == after) { "Production library changed during export" }
        result
    }

private data class SchemaEntry(val type: String, val name: String, val sql: String)

private fun readSchema(source: SQLiteDatabase): List<SchemaEntry> = source.rawQuery(
    "SELECT type, name, sql FROM sqlite_master WHERE type IN ('table', 'index') " +
        "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' AND sql IS NOT NULL ORDER BY name", null,
).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(SchemaEntry(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
    }
}

private suspend fun copyRows(source: SQLiteDatabase, destination: SQLiteDatabase, table: String) {
    source.rawQuery("SELECT * FROM ${quoteIdentifier(table)}", null).use { cursor ->
        while (cursor.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val values = ContentValues(cursor.columnCount)
            cursor.columnNames.forEachIndexed { column, name ->
                when (cursor.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> values.putNull(name)
                    Cursor.FIELD_TYPE_INTEGER -> values.put(name, cursor.getLong(column))
                    Cursor.FIELD_TYPE_FLOAT -> values.put(name, cursor.getDouble(column))
                    Cursor.FIELD_TYPE_BLOB -> values.put(name, cursor.getBlob(column))
                    else -> values.put(name, cursor.getString(column))
                }
            }
            destination.insertOrThrow(table, null, values)
        }
    }
}

private fun quoteIdentifier(name: String): String = "\"${name.replace("\"", "\"\"")}\""

private val libraryTables = setOf(
    "room_master_table", "codices", "codex_automatic_tags", "codex_items", "posts", "liked_posts",
    "recent_watched", "recent_searches",
)
