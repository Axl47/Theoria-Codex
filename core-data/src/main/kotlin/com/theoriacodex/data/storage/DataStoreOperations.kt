package com.theoriacodex.data.storage

import androidx.datastore.core.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun <T> DataStore<T>.updateDataInBackground(transform: suspend (T) -> T): T =
    withContext(Dispatchers.IO) { updateData(transform) }
