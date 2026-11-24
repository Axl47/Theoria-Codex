package com.theoriacodex.app.statistics

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.theoriacodex.app.backup.AutoBackupFolder
import com.theoriacodex.app.backup.AutoBackupStatus
import com.theoriacodex.app.backup.AutoBackupStore
import com.theoriacodex.app.backup.AutoBackupWorkflow
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.data.repository.UsageDurationDelta
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class AppBackgroundObserverTest {
    @Test
    fun `process stop records the last usage, flushes, then backs up what it recorded (ADR 0215)`() = runTest {
        var elapsed = 0L
        val calls = mutableListOf<String>()
        val memory = InMemoryStatisticsRepository()
        val repository = object : StatisticsRepository by memory {
            override suspend fun addUsageDuration(delta: UsageDurationDelta) {
                yield()
                memory.addUsageDuration(delta)
                calls += "usage"
            }
        }
        val tracker = AppUsageTracker(repository, backgroundScope, { elapsed }, tickIntervalMs = 0L)
        val autoBackup = AutoBackupWorkflow(
            store = object : AutoBackupStore {
                override fun read() = AutoBackupStatus(folderUri = "folder")
                override fun write(status: AutoBackupStatus) = Unit
            },
            openFolder = {
                object : AutoBackupFolder {
                    override fun names() = emptyList<String>()
                    override fun write(name: String, bytes: ByteArray) = Unit
                    override fun delete(name: String) = Unit
                }
            },
            export = {
                calls += "backup ${memory.observeStatistics().first().totalForegroundMs}"
                ByteArray(0)
            },
            scope = backgroundScope,
        )
        val process = ProcessLifecycle()
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        process.registry.addObserver(AppBackgroundObserver(tracker, { calls += "flush" }, autoBackup, backgroundScope))
        runCurrent()
        elapsed = 2_000L

        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        runCurrent()

        assertEquals(listOf("usage", "flush", "backup 2000"), calls)
    }

    private class ProcessLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
