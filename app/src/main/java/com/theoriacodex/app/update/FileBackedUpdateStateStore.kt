package com.theoriacodex.app.update

import com.google.gson.Gson
import java.io.File

class FileBackedUpdateStateStore(
    private val file: File,
    private val gson: Gson = Gson(),
) : UpdateStateStore {
    private val lock = Any()

    override fun snapshot(): UpdateStateSnapshot = synchronized(lock) {
        readLocked()
    }

    override fun setLastSeenReleaseId(releaseId: Long?) {
        synchronized(lock) {
            val current = readLocked()
            writeLocked(current.copy(lastSeenReleaseId = releaseId))
        }
    }

    override fun setPendingInstall(releaseId: Long?, versionCode: Int?) {
        synchronized(lock) {
            val current = readLocked()
            writeLocked(
                current.copy(
                    pendingInstallReleaseId = releaseId,
                    pendingInstallVersionCode = versionCode,
                )
            )
        }
    }

    override fun clearPendingInstall() {
        synchronized(lock) {
            val current = readLocked()
            writeLocked(
                current.copy(
                    pendingInstallReleaseId = null,
                    pendingInstallVersionCode = null,
                )
            )
        }
    }

    override fun setIgnoredRelease(releaseId: Long?) {
        synchronized(lock) {
            val current = readLocked()
            writeLocked(
                current.copy(
                    ignoredReleaseId = releaseId,
                )
            )
        }
    }

    override fun setRemindLater(releaseId: Long?, untilEpochMs: Long?) {
        synchronized(lock) {
            val current = readLocked()
            writeLocked(
                current.copy(
                    remindLaterReleaseId = releaseId,
                    remindLaterUntilEpochMs = untilEpochMs,
                )
            )
        }
    }

    override fun clearPromptDeferrals() {
        synchronized(lock) {
            val current = readLocked()
            writeLocked(
                current.copy(
                    ignoredReleaseId = null,
                    remindLaterReleaseId = null,
                    remindLaterUntilEpochMs = null,
                )
            )
        }
    }

    private fun readLocked(): UpdateStateSnapshot {
        if (!file.exists()) return UpdateStateSnapshot()
        val body = runCatching { file.readText() }.getOrDefault("")
        if (body.isBlank()) return UpdateStateSnapshot()
        return runCatching {
            gson.fromJson(body, UpdateStateSnapshot::class.java)
        }.getOrDefault(UpdateStateSnapshot())
    }

    private fun writeLocked(snapshot: UpdateStateSnapshot) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(gson.toJson(snapshot))
        if (file.exists()) {
            file.delete()
        }
        temp.renameTo(file)
    }
}
