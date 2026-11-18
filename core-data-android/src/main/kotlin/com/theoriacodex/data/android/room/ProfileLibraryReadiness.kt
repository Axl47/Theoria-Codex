package com.theoriacodex.data.android.room

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the library database and runs pending Room migrations before ordinary routes or a developer
 * import admit writes, so a database that cannot be opened fails at startup, where it is reported.
 */
suspend fun openProfileLibrary(database: TheoriaRoomDatabase) {
    withContext(Dispatchers.IO) { database.openHelper.writableDatabase }
}
