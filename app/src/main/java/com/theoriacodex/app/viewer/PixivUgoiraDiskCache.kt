package com.theoriacodex.app.viewer

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** Cache only the timing needed to reuse this exact ZIP; never persist credentials or reflected Kotlin models. */
internal fun readUgoiraFrameMetadata(archive: File): List<UgoiraFrameSpec>? = runCatching {
    val sidecar = ugoiraMetadataFile(archive)
    if (!archive.isFile || !sidecar.isFile || sidecar.length() !in 1L..UGOIRA_METADATA_MAX_BYTES) {
        return null
    }
    val root = JsonParser.parseString(sidecar.readText()).asJsonObject
    if (root.get("version").asInt != 1 || root.get("archiveBytes").asLong != archive.length() ||
        root.get("archiveModifiedMs").asLong != archive.lastModified()
    ) return null
    val frames = root.getAsJsonArray("frames")
    if (frames.size() !in 1..UGOIRA_MAX_FRAME_COUNT) return null
    frames.map { element ->
        val frame = element.asJsonObject
        UgoiraFrameSpec(frame.get("file").asString, frame.get("delay").asInt.coerceAtLeast(16))
    }
}.getOrNull()

internal fun writeUgoiraFrameMetadata(archive: File, frames: List<UgoiraFrameSpec>) {
    val destination = ugoiraMetadataFile(archive)
    val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
    // Persistence is optional; a later miss simply re-fetches metadata.
    runCatching {
        val root = JsonObject().apply {
            addProperty("version", 1)
            addProperty("archiveBytes", archive.length())
            addProperty("archiveModifiedMs", archive.lastModified())
            add("frames", JsonArray().apply {
                frames.forEach { frame ->
                    add(JsonObject().apply {
                        addProperty("file", frame.fileName)
                        addProperty("delay", frame.delayMs)
                    })
                }
            })
        }
        temporary.writeText(root.toString())
        check(temporary.length() <= UGOIRA_METADATA_MAX_BYTES && temporary.renameTo(destination))
    }
    temporary.delete()
}

internal fun ugoiraMetadataFile(archive: File): File = File(archive.parentFile, "${archive.name}.json")

private const val UGOIRA_METADATA_MAX_BYTES = 256L * 1024L
