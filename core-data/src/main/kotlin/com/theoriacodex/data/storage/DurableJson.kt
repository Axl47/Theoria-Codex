package com.theoriacodex.data.storage

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The single kotlinx.serialization configuration for saved data (files, Room payloads, exports) and for the app's
 * fixed-shape network, asset, and developer-copy JSON. Each flag keeps data written by Gson, or read by an older
 * build, behaving as it did under the plain `Gson()` it replaces; golden files captured from Gson pin each record.
 * Rationale and the full compatibility contract: .docs/exec/gson-to-kotlinx-serialization.html.
 */
@OptIn(ExperimentalSerializationApi::class)
val DurableJson: Json = Json {
    // Newer files and retired fields must not be rejected.
    ignoreUnknownKeys = true
    // Backup validation requires defaulted keys to be present, as Gson wrote them.
    encodeDefaults = true
    // Gson omits nulls, and some readers treat a present key as data.
    explicitNulls = false
    // A stored null on a defaulted field repairs to the default instead of resetting the store.
    coerceInputValues = true
    // Gson accepted quoted primitives such as "5" for an Int and "true" for a Boolean.
    isLenient = true
}
