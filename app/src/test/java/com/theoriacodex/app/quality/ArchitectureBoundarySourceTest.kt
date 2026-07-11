package com.theoriacodex.app.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureBoundarySourceTest {
    private val userDirectory = requireNotNull(System.getProperty("user.dir")) {
        "The JVM did not expose user.dir"
    }
    private val repositoryRoot: File = generateSequence(File(userDirectory).absoluteFile) {
        it.parentFile
    }.firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate the repository root from $userDirectory")

    @Test
    fun `production UI collects observable state with lifecycle awareness`() {
        val violations = appProductionSources()
            .filter { source -> Regex("""\bcollectAsState\s*\(""").containsMatchIn(source.readText()) }
            .map(::relativePath)
            .toList()

        assertNoViolations(
            rule = "Use collectAsStateWithLifecycle in production UI",
            violations = violations,
        )
    }

    @Test
    fun `coordinators engines and orchestrators stay independent from Compose state`() {
        val ownerName = Regex("""(?:Coordinator|Engine|Orchestrator)\.kt$""")
        val forbiddenImport = Regex(
            """^import androidx\.compose\.runtime\.mutable(?:State|IntState|LongState|FloatState|DoubleState)""",
            setOf(RegexOption.MULTILINE),
        )
        val violations = productionSources()
            .filter { source -> ownerName.containsMatchIn(source.name) }
            .filter { source -> forbiddenImport.containsMatchIn(source.readText()) }
            .map(::relativePath)
            .toList()

        assertNoViolations(
            rule = "Coordinator, engine, and orchestrator state must remain platform independent",
            violations = violations,
        )
    }

    @Test
    fun `file backed repositories are constructed only by the application container`() {
        val container = "app/src/main/java/com/theoriacodex/app/di/TheoriaAppContainer.kt"
        val fileBackedConstruction = Regex("""\bFileBacked[A-Za-z0-9_]*Repository\s*\(""")
        val violations = appProductionSources()
            .filter { source -> relativePath(source) != container }
            .filter { source -> fileBackedConstruction.containsMatchIn(source.readText()) }
            .map(::relativePath)
            .toList()

        assertNoViolations(
            rule = "Durable repository construction belongs to TheoriaAppContainer",
            violations = violations,
        )
        assertTrue(
            "The durable-construction guard became vacuous because the application container owns no file-backed repository",
            fileBackedConstruction.containsMatchIn(File(repositoryRoot, container).readText()),
        )
    }

    private fun appProductionSources(): Sequence<File> = kotlinSourcesUnder("app/src/main")

    private fun productionSources(): Sequence<File> {
        return repositoryRoot.listFiles().orEmpty()
            .asSequence()
            .filter { directory ->
                directory.isDirectory &&
                    (directory.name == "app" || directory.name.startsWith("core-"))
            }
            .flatMap { module ->
                File(module, "src/main").walkTopDown()
                    .filter(File::isFile)
                    .filter { it.extension == "kt" }
            }
    }

    private fun kotlinSourcesUnder(relativeDirectory: String): Sequence<File> {
        return File(repositoryRoot, relativeDirectory)
            .walkTopDown()
            .filter(File::isFile)
            .filter { it.extension == "kt" }
    }

    private fun relativePath(file: File): String = file.relativeTo(repositoryRoot).invariantSeparatorsPath

    private fun assertNoViolations(rule: String, violations: List<String>) {
        assertTrue(
            buildString {
                append(rule)
                if (violations.isNotEmpty()) {
                    append(":\n")
                    append(violations.joinToString(separator = "\n"))
                }
            },
            violations.isEmpty(),
        )
    }
}
