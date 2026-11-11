// Records each task's duration and outcome for scripts/check.sh's CI timing report.
// Applied with `--init-script`; appends one JSON object per task to
// build/reports/timings/gradle-tasks.jsonl when the build finishes. Uses a build-event listener,
// so it works with the strict configuration cache.
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult

abstract class TaskTimingsService :
    BuildService<TaskTimingsService.Params>,
    OperationCompletionListener,
    AutoCloseable {
    interface Params : BuildServiceParameters {
        val output: RegularFileProperty
    }

    private val entries = ConcurrentLinkedQueue<String>()

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val result = event.result
        val outcome = when (result) {
            is TaskFailureResult -> "failed"
            is TaskSkippedResult -> "skipped"
            // Gradle reports cache hits as up-to-date too, so check the cache first.
            is TaskSuccessResult -> when {
                result.isFromCache -> "from-cache"
                result.isUpToDate -> "up-to-date"
                else -> "executed"
            }
            else -> return
        }
        val seconds = String.format(Locale.ROOT, "%.3f", (result.endTime - result.startTime) / 1000.0)
        entries += """{"path": "${event.descriptor.taskPath}", "seconds": $seconds, "outcome": "$outcome"}"""
    }

    override fun close() {
        if (entries.isEmpty()) return
        val file = parameters.output.get().asFile
        file.parentFile.mkdirs()
        file.appendText(entries.joinToString(separator = "\n", postfix = "\n"))
    }
}

abstract class TaskTimingsPlugin @Inject constructor(
    private val events: BuildEventsListenerRegistry,
) : Plugin<Gradle> {
    override fun apply(gradle: Gradle) {
        gradle.settingsEvaluated {
            val output = rootDir.resolve("build/reports/timings/gradle-tasks.jsonl")
            val service = gradle.sharedServices.registerIfAbsent("taskTimings", TaskTimingsService::class.java) {
                parameters.output.set(output)
            }
            events.onTaskCompletion(service)
        }
    }
}

apply<TaskTimingsPlugin>()
