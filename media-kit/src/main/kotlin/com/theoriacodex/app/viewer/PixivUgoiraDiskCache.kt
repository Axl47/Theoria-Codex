package com.theoriacodex.app.viewer

import com.theoriacodex.data.storage.DurableJson
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Cache only the timing needed to reuse this exact ZIP; never persist credentials or Kotlin models. */
internal fun readUgoiraFrameMetadata(archive: File): List<UgoiraFrameSpec>? = runCatching {
    val sidecar = ugoiraMetadataFile(archive)
    if (!archive.isFile || !sidecar.isFile || sidecar.length() !in 1L..UGOIRA_METADATA_MAX_BYTES) {
        return null
    }
    val root = DurableJson.decodeFromString(UgoiraCacheIndex.serializer(), sidecar.readText())
    if (root.version != 1 || root.archiveBytes != archive.length() || root.archiveModifiedMs != archive.lastModified()) {
        return null
    }
    if (root.frames.size !in 1..UGOIRA_MAX_FRAME_COUNT) return null
    root.frames.map { frame -> UgoiraFrameSpec(frame.file, frame.delay.coerceAtLeast(16)) }
}.getOrNull()

internal fun writeUgoiraFrameMetadata(archive: File, frames: List<UgoiraFrameSpec>) {
    val destination = ugoiraMetadataFile(archive)
    val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
    // Persistence is optional; a later miss simply re-fetches metadata.
    runCatching {
        val root = UgoiraCacheIndex(
            version = 1,
            archiveBytes = archive.length(),
            archiveModifiedMs = archive.lastModified(),
            frames = frames.map { frame -> UgoiraCacheFrame(frame.fileName, frame.delayMs) },
        )
        temporary.writeText(DurableJson.encodeToString(UgoiraCacheIndex.serializer(), root))
        check(temporary.length() <= UGOIRA_METADATA_MAX_BYTES && temporary.renameTo(destination))
    }
    temporary.delete()
}

internal fun ugoiraMetadataFile(archive: File): File = File(archive.parentFile, "${archive.name}.json")

/** The `<archive>.zip.json` sidecar; it only counts while its archive's size and timestamp still match. */
@Serializable
internal data class UgoiraCacheIndex(
    @SerialName("version")
    val version: Int,
    @SerialName("archiveBytes")
    val archiveBytes: Long,
    @SerialName("archiveModifiedMs")
    val archiveModifiedMs: Long,
    @SerialName("frames")
    val frames: List<UgoiraCacheFrame>,
)

@Serializable
internal data class UgoiraCacheFrame(
    @SerialName("file")
    val file: String,
    @SerialName("delay")
    val delay: Int,
)

private const val UGOIRA_METADATA_MAX_BYTES = 256L * 1024L
