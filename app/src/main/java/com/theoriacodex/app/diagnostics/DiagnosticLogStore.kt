package com.theoriacodex.app.diagnostics

import com.theoriacodex.domain.diagnostics.DiagnosticRedaction
import com.theoriacodex.domain.diagnostics.DiagnosticSink
import java.io.File
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val MAX_EVENT_FILE_BYTES = 256L * 1024
private const val MAX_PENDING_EVENTS = 512
private const val MAX_CRASH_FILES = 5
private const val MAX_STACK_CHARS = 6_000

/**
 * Private on-device failure log. Text is redacted before it reaches disk and both files are bounded, so the log
 * can stay on permanently. Events are appended on one background thread because failures are reported from the UI.
 */
internal class DiagnosticLogStore(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
) : DiagnosticSink {
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "theoria-diagnostics").apply { isDaemon = true } }
    private val pending = AtomicInteger()
    private val current = directory.resolve("events.log")
    private val previous = directory.resolve("events.previous.log")
    private var lastKey: String? = null
    private var repeats = 0

    override fun record(area: String, message: String, error: Throwable?) {
        // Dropping during a flood keeps memory bounded; the earlier lines already show what went wrong.
        if (pending.incrementAndGet() > MAX_PENDING_EVENTS) {
            pending.decrementAndGet()
            return
        }
        val timestamp = now()
        writer.execute {
            try {
                runCatching { append(timestamp, area, message, error) }
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private fun append(timestamp: Long, area: String, message: String, error: Throwable?) {
        val key = "$area|$message|${error?.javaClass?.name}"
        if (key == lastKey) {
            repeats += 1
            return
        }
        check(directory.isDirectory || directory.mkdirs()) { "Could not create the diagnostics directory" }
        val repeatLine = if (repeats > 0) "  (repeated $repeats more times)\n" else ""
        lastKey = key
        repeats = 0
        val detail = error?.let { "\n" + it.stackTraceToString().take(MAX_STACK_CHARS).trimEnd().prependIndent("  ") }.orEmpty()
        val line = "${Instant.ofEpochMilli(timestamp)} [$area] $message$detail"
        current.appendText(repeatLine + DiagnosticRedaction.redact(line) + "\n")
        if (current.length() > MAX_EVENT_FILE_BYTES) {
            previous.delete()
            current.renameTo(previous)
        }
    }

    /** Runs on the crashing thread and blocks: the process is about to die, so nothing can be queued. */
    fun writeCrash(thread: Thread, error: Throwable) {
        if (!directory.isDirectory && !directory.mkdirs()) return
        val timestamp = now()
        val report = "${Instant.ofEpochMilli(timestamp)} on thread ${thread.name}\n${error.stackTraceToString()}"
        directory.resolve("crash-$timestamp.txt").writeText(DiagnosticRedaction.redact(report))
        crashFiles().drop(MAX_CRASH_FILES).forEach(File::delete)
    }

    /** Oldest line first, after every event recorded before this call has been written. */
    fun readEvents(): String = onWriter {
        listOf(previous, current).filter(File::isFile).joinToString(separator = "") { it.readText() }
    }

    /** Newest report first. Runs on the writer so [clear] cannot delete a file partway through. */
    fun readCrashes(): List<String> = onWriter { crashFiles().map(File::readText) }

    fun clear() = onWriter {
        directory.listFiles().orEmpty().forEach(File::delete)
        lastKey = null
        repeats = 0
    }

    private fun crashFiles(): List<File> = directory.listFiles { file -> file.name.startsWith("crash-") }
        .orEmpty().sortedByDescending(File::getName)

    private fun <T> onWriter(block: () -> T): T = writer.submit(Callable { block() }).get()
}

/** Keeps the stack of any crash, then hands over so Android still ends the process as usual. */
internal fun installCrashCapture(store: DiagnosticLogStore) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching { store.writeCrash(thread, error) }
        previous?.uncaughtException(thread, error)
    }
}
