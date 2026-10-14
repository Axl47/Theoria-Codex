package com.theoriacodex.data.android.room

import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import java.io.File

/** Complete library migration/archival before ordinary routes or a developer import admits writes. */
suspend fun awaitProfileLibraryStores(
    database: TheoriaRoomDatabase,
    storageDirectory: File,
    recoveryRegistry: LegacyJsonRecoveryRegistry = LegacyJsonRecoveryRegistry(),
) {
    awaitCodexLikesStore(RoomLegacyJsonImporter(database), storageDirectory)
    awaitRecentsStore(RoomRecentsLegacyImporter(database, recoveryRegistry = recoveryRegistry), storageDirectory)
}

private suspend fun awaitCodexLikesStore(importer: RoomLegacyJsonImporter, storageDirectory: File) {
    when (val imported = importer.importIfNeeded(storageDirectory)) {
        is LegacyJsonImportResult.Imported,
        is LegacyJsonImportResult.AdoptedVerifiedDestination,
        is LegacyJsonImportResult.AlreadyImported -> Unit

        is LegacyJsonImportResult.SplitBrain -> throw LegacyJsonMigrationException(
            "Legacy Codex/Likes data changed after its verified Room migration",
        )
        is LegacyJsonImportResult.DestinationConflict -> throw LegacyJsonMigrationException(
            "Room Codex/Likes data conflicts with the retained legacy snapshot",
        )
        is LegacyJsonImportResult.DestinationDrift -> throw LegacyJsonMigrationException(
            "Room Codex/Likes data no longer matches its verified migration proof",
        )
        is LegacyJsonImportResult.InvalidStoredProof -> throw LegacyJsonMigrationException(
            "Room Codex/Likes migration proof is invalid: ${imported.reason}",
        )
    }
    when (val archived = importer.archiveImportedSources(storageDirectory)) {
        is LegacyArchiveResult.Complete -> Unit
        LegacyArchiveResult.NotImported -> throw LegacyJsonMigrationException(
            "Room Codex/Likes migration completed without a durable proof",
        )
        is LegacyArchiveResult.InvalidStoredProof -> throw LegacyJsonMigrationException(
            "Room Codex/Likes archive proof is invalid: ${archived.reason}",
        )
        is LegacyArchiveResult.Partial -> throw LegacyJsonMigrationException(
            "Room Codex/Likes legacy archive is incomplete for ${archived.blockedFile}: " +
                archived.reason,
        )
    }
}

private suspend fun awaitRecentsStore(importer: RoomRecentsLegacyImporter, storageDirectory: File) {
    when (val recents = importer.importAndArchive(storageDirectory)) {
        is RecentsImportResult.Imported,
        is RecentsImportResult.AlreadyImported -> Unit

        is RecentsImportResult.SourceChanged -> throw LegacyJsonMigrationException(
            "Legacy Recent activity changed after its verified Room migration",
        )
        is RecentsImportResult.DestinationConflict -> throw LegacyJsonMigrationException(
            "Room Recent activity conflicts with the retained legacy snapshot",
        )
        is RecentsImportResult.DestinationDrift -> throw LegacyJsonMigrationException(
            "Room Recent activity no longer matches its verified migration proof",
        )
        is RecentsImportResult.InvalidProof -> throw LegacyJsonMigrationException(
            "Room Recent activity migration proof is invalid: ${recents.reason}",
        )
        is RecentsImportResult.ArchiveFailed -> throw LegacyJsonMigrationException(
            "Recent activity legacy archive is incomplete: ${recents.reason}",
        )
    }
}
