package com.theoriacodex.data.android.room

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun openProfileLibrary(database: TheoriaRoomDatabase) {
    withContext(Dispatchers.IO) { database.openHelper.writableDatabase }
}
