package com.theoriacodex.app.di

import android.content.Context
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.ExtensionRepo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
        @Suppress("StoredJsonUsesDurableJsonRule")
        private val json = Json { ignoreUnknownKeys = true }

        fun on(context: Context) = DeviceExtensionChoices(File(context.noBackupFilesDir, "extension_choices.json"))
    }
}
