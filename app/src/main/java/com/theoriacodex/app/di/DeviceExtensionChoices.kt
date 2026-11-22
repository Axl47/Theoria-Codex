package com.theoriacodex.app.di

import android.content.Context
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.mihonhost.MihonHost
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What this device decided and knows about extensions, in a file Android neither backs up nor transfers to a new
 * phone: turning an extension on is a decision made on a device, about the code installed on it. Nothing else is
 * ever read: not a profile backup, and not the settings store, where builds before the first release with extensions
 * kept these choices (see [forgetLegacyExtensionData]).
 */
internal class DeviceExtensionChoices(private val file: File) : ExtensionChoiceStore {
    private val lock = Mutex()

    @Volatile private var stored: Record? = runCatching { json.decodeFromString(Record.serializer(), file.readText()) }.getOrNull()

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

    private fun load(): Record = stored ?: Record()

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
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun on(context: Context) = DeviceExtensionChoices(File(context.noBackupFilesDir, "extension_choices.json"))
    }
}

/**
 * Removes what debug builds kept where Android backs it up, before extensions reached a release: the choices in the
 * settings store and extension preferences in `shared_prefs`. Nothing is carried over, so an extension that was on in
 * such a build is turned on once more. Runs at every start; it writes nothing once both are gone.
 */
internal suspend fun forgetLegacyExtensionData(context: Context, settings: SettingsRepository) {
    if (settings.observeSettings().first().extensions != ExtensionChoices()) settings.updateSettings { it.copy(extensions = ExtensionChoices()) }
    MihonHost.deleteLegacyPreferences(context)
}
