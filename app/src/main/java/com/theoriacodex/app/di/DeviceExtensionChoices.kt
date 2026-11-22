package com.theoriacodex.app.di

import android.content.Context
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What this device decided and knows about extensions, in a file Android neither backs up nor transfers to a new
 * phone: turning an extension on is a decision made on a device, about the code installed on it.
 *
 * Through 1.9.2 the choices were in the settings store, which Android does back up. They are moved here once, and
 * only where [updatedInPlace] says the app was updated over an earlier install. On a fresh install the settings can
 * only have come from a restore, so what they say about extensions is dropped and nothing is on. Removable two
 * releases after the one that added it, together with `SettingsRecord.trustedExtensions`.
 */
internal class DeviceExtensionChoices(
    private val file: File,
    /** Empties the settings store's extension choices and returns what they were. */
    private val takeFromSettings: suspend () -> ExtensionChoices,
    private val updatedInPlace: () -> Boolean,
) : ExtensionChoiceStore {
    private val lock = Mutex()

    // Read when the app's data opens: the Series tab needs `extensionSeen` before the first frame.
    @Volatile private var stored: Record? = runCatching { json.decodeFromString(Record.serializer(), file.readText()) }.getOrNull()

    /** Whether an extension was on the device when the app last looked, so startup need not look. */
    val extensionSeen: Boolean get() = stored?.extensionSeen == true

    suspend fun rememberExtensionSeen(seen: Boolean) = change { it.copy(extensionSeen = seen) }

    override suspend fun choices(): ExtensionChoices = lock.withLock { load() }.let { record ->
        ExtensionChoices(record.trusted, record.languages.mapValues { (_, languages) -> languages.toSet() })
    }

    override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) = change { record ->
        val changed = transform(ExtensionChoices(record.trusted, record.languages.mapValues { (_, languages) -> languages.toSet() }))
        record.copy(trusted = changed.trusted, languages = changed.languages.mapValues { (_, languages) -> languages.sorted() })
    }

    private suspend fun change(transform: (Record) -> Record) = lock.withLock {
        val before = load()
        val after = transform(before)
        if (after != before) write(after)
    }

    private suspend fun load(): Record = stored ?: run {
        val fromSettings = takeFromSettings()
        val moved = if (updatedInPlace()) fromSettings else ExtensionChoices()
        Record(moved.trusted, moved.languages.mapValues { (_, languages) -> languages.sorted() }).also { write(it) }
    }

    private suspend fun write(record: Record) = withContext(Dispatchers.IO) {
        val pending = File(file.path + ".new")
        pending.writeText(json.encodeToString(Record.serializer(), record))
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        stored = record
    }

    @Serializable
    private data class Record(
        val trusted: Map<String, String> = emptyMap(),
        val languages: Map<String, List<String>> = emptyMap(),
        val extensionSeen: Boolean = false,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun on(context: Context, settings: SettingsRepository) = DeviceExtensionChoices(
            file = File(context.noBackupFilesDir, "extension_choices.json"),
            takeFromSettings = {
                var before = ExtensionChoices()
                settings.updateSettings { before = it.extensions; it.copy(extensions = ExtensionChoices()) }
                before
            },
            updatedInPlace = { context.packageManager.getPackageInfo(context.packageName, 0).let { it.firstInstallTime != it.lastUpdateTime } },
        )
    }
}
