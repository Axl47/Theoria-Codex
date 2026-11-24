package com.theoriacodex.app.di

import android.content.Context
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.extensionhost.ExtensionHost
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
 * phone: turning an extension on is a decision made on a device, about the code installed on it. A profile backup
 * carries only the repos ([com.theoriacodex.domain.series.ExtensionRepos.restore] merges them in); the trusted
 * extensions and languages stay on the device. Nothing else is read: not the settings store, where builds before
 * the first release with extensions kept these choices (see [forgetLegacyExtensionData]).
 */
internal class DeviceExtensionChoices(private val file: File) : ExtensionChoiceStore {
    private val lock = Mutex()

    @Volatile private var stored: Record? = runCatching { json.decodeFromString(Record.serializer(), file.readText()) }.getOrNull()

    override suspend fun choices(): ExtensionChoices = lock.withLock { load() }.choices()

    override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) = change { record ->
        val changed = transform(record.choices())
        Record(changed.trusted, changed.languages.mapValues { (_, languages) -> languages.sorted() }, changed.owners, changed.repos.map(::RepoRecord))
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
        val owners: Map<String, String> = emptyMap(),
        val repos: List<RepoRecord> = emptyList(),
    ) {
        fun choices() = ExtensionChoices(trusted, languages.mapValues { (_, languages) -> languages.toSet() }, owners, repos.map(RepoRecord::repo))
    }

    @Serializable
    private data class RepoRecord(val url: String, val name: String, val signingKey: String) {
        constructor(repo: ExtensionRepo) : this(repo.url, repo.name, repo.signingKey)

        fun repo() = ExtensionRepo(url, name, signingKey)
    }

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
    ExtensionHost.deleteLegacyPreferences(context)
}
