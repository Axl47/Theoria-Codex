package com.theoriacodex.sources

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the source boundary main-safe by reading this module's source. The boundary is every override of a
 * suspending function declared in SourceAdapter.kt, every public suspending member of [BOUNDARY_CLASSES], and
 * [BOUNDARY_FUNCTIONS]. Each must be written as `= sourceCall {` or `= sourceCall(callContext) {` and end where
 * that block ends; production code may never choose the dispatcher. SourceMainSafetyContractTest shows the
 * wrapper itself works.
 */
class SourceEntryPointCompletenessTest {
    private val moduleDir = File(System.getProperty("user.dir"))
    private val repoDir = moduleDir.parentFile
    private val sources: List<SourceFile> = File(moduleDir, "src/main/kotlin").walkTopDown()
        .filter { it.extension == "kt" }
        .map { SourceFile(it.relativeTo(moduleDir).path, it.readText()) }
        .sortedBy { it.path }
        .toList()

    @Test
    fun `every boundary function runs its whole body through sourceCall`() {
        val interfaceNames = Regex("""suspend\s+fun\s+(\w+)""")
            .findAll(File(repoDir, SOURCE_ADAPTER_FILE).readText()).map { it.groupValues[1] }.toSet()
        assertTrue("SourceAdapter.kt declares no suspending functions", "search" in interfaceNames)

        val boundary = sources.flatMap { file -> boundaryFunctions(file, interfaceNames) }
        val overrideCount = sources.sumOf { file ->
            Regex("""\b(?:override\s+suspend|suspend\s+override)\s+fun\s+(\w+)\s*\(""").findAll(file.code).count { it.groupValues[1] in interfaceNames }
        }
        assertEquals(
            "the scan must find every interface override",
            overrideCount,
            boundary.count { it.kind == Kind.INTERFACE },
        )
        BOUNDARY_CLASSES.forEach { name ->
            assertTrue("$name has no public suspending functions", boundary.any { it.owner == name })
        }
        BOUNDARY_FUNCTIONS.forEach { name ->
            assertTrue("$name not found", boundary.any { it.kind == Kind.TOP_LEVEL && it.name == name })
        }

        val violations = boundary.mapNotNull { function -> function.violation()?.let { "${function.where}: $it" } }
        assertEquals("boundary functions not written as `= sourceCall {`", emptyList<String>(), violations)
    }

    @Test
    fun `production code never chooses the dispatcher`() {
        val productionFiles = repoDir.listFiles().orEmpty()
            .map { File(it, "src/main") }
            .filter(File::isDirectory)
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue("no production sources found", productionFiles.size > sources.size)
        val contextOwners = productionFiles.filter { CALL_CONTEXT_DECLARATION in it.readText() }
            .map { it.nameWithoutExtension }
        val violations = productionFiles.flatMap { file ->
            val text = codeOnly(file.readText())
            val where = file.relativeTo(repoDir).path
            val stray = Regex("""\bcallContext\b""").findAll(text)
                .filterNot { match ->
                    ALLOWED_CALL_CONTEXT_USES.any { (use, offset) -> text.startsWith(use, match.range.first - offset) }
                }
                .map { match -> "$where:${lineOf(text, match.range.first)}: callContext used outside its declaration" }
            // A positional argument would choose the dispatcher without naming callContext.
            val positional = contextOwners.flatMap { owner ->
                Regex("""(?<!class )\b$owner\(""").findAll(text).mapNotNull { match ->
                    val open = match.range.last
                    val arguments = text.substring(open, matchingClose(text, open, '(', ')'))
                    "$where:${lineOf(text, open)}: $owner built with a dispatcher"
                        .takeIf { DISPATCHER_WORDS.containsMatchIn(arguments) }
                }.toList()
            }
            stray.toList() + positional
        }
        assertEquals(emptyList<String>(), violations)
    }

    private fun boundaryFunctions(file: SourceFile, interfaceNames: Set<String>): List<BoundaryFunction> {
        val classBodies = BOUNDARY_CLASSES.mapNotNull { name ->
            Regex("""(?m)^class\s+$name\b""").find(file.text)?.let { match ->
                val open = classBodyOpen(file.text, match.range.last)
                name to (open..matchingClose(file.text, open, '{', '}'))
            }
        }
        return FUNCTION.findAll(file.code).mapNotNull { match ->
            val modifiers = match.groupValues[2].split(Regex("""\s+""")).toSet()
            val name = match.groupValues[3]
            val at = match.range.first + match.groupValues[1].length
            val owner = classBodies.firstOrNull { (_, body) -> at in body && braceDepth(file.text, body.first, at) == 1 }
            val isPublic = modifiers.none { it in setOf("private", "internal", "protected") }
            val kind = when {
                "override" in modifiers && "suspend" in modifiers && name in interfaceNames -> Kind.INTERFACE
                owner != null && "suspend" in modifiers && isPublic -> Kind.CLASS_MEMBER
                name in BOUNDARY_FUNCTIONS && match.groupValues[1].isEmpty() && "suspend" in modifiers -> Kind.TOP_LEVEL
                else -> return@mapNotNull null
            }
            BoundaryFunction(file, kind, owner?.first, name, at, match.range.last, match.groupValues[1])
        }.toList()
    }

    private class SourceFile(val path: String, val text: String) {
        /** [text] with comments and string contents blanked, so neither can satisfy or trip the scan. */
        val code = codeOnly(text)
    }

    private enum class Kind { INTERFACE, CLASS_MEMBER, TOP_LEVEL }

    private class BoundaryFunction(
        val file: SourceFile,
        val kind: Kind,
        val owner: String?,
        val name: String,
        val start: Int,
        /** The parameter list's opening parenthesis. */
        val paramsOpen: Int,
        val indent: String,
    ) {
        val where get() = "${file.path}:${lineOf(file.text, start)} $name"

        /** Why this function is not exactly `… = sourceCall {` ending with its block, or null when it is. */
        fun violation(): String? {
            val text = file.code
            val afterParams = matchingClose(text, paramsOpen, '(', ')') + 1
            val form = WRAPPER.matchAt(text, afterParams) ?: return "body is not `= sourceCall {` or `= sourceCall(callContext) {`"
            val blockEnd = matchingClose(text, form.range.last, '{', '}')
            val restOfLine = text.substring(blockEnd + 1).substringBefore('\n')
            if (restOfLine.isNotBlank() && !restOfLine.trim().startsWith("//")) return "work appended after the sourceCall block"
            val next = nextSignificantLine(text, blockEnd + 1 + restOfLine.length) ?: return null
            val nextIndent = next.length - next.trimStart().length
            if (nextIndent > indent.length || !DECLARATION_START.containsMatchIn(next.trimStart())) {
                return "work appended after the sourceCall block"
            }
            return null
        }
    }

    private companion object {
        const val SOURCE_ADAPTER_FILE = "core-domain/src/main/kotlin/com/theoriacodex/domain/adapter/SourceAdapter.kt"

        /** Classes outside the adapters that app code calls directly with a provider response to handle. */
        val BOUNDARY_CLASSES = listOf("PixivAuthApi", "PixivTokenCoordinator", "SiteProber", "SiteHealthCheck")

        /** Top-level functions app code calls directly with a provider response to handle. */
        val BOUNDARY_FUNCTIONS = emptySet<String>()

        /** A constructor parameter that defaults to the background dispatcher; only tests pass another. */
        const val CALL_CONTEXT_DECLARATION = "callContext: CoroutineContext = Dispatchers.Default,"

        /**
         * The only code callContext may appear in, with the offset of the name in each: its declaration, the
         * wrapper, and a subclass handing its own parameter to its base class.
         */
        val ALLOWED_CALL_CONTEXT_USES = listOf(
            CALL_CONTEXT_DECLARATION to 0,
            "sourceCall(callContext) {" to 11,
            "callContext = callContext," to 0,
            "callContext = callContext," to 14,
        )
        val DISPATCHER_WORDS = Regex("""Dispatchers|CoroutineContext|coroutineContext|CoroutineDispatcher""")

        val FUNCTION = Regex(
            """(?m)^([ \t]*)(?:@[\w.]+(?:\([^)\n]*\))?\s+)*((?:(?:public|internal|private|protected|override|suspend|final|open|inline|operator)\s+)*)""" +
                """fun\s+(?:<[^>]*>\s*)?(\w+)\s*\(""",
        )
        val WRAPPER = Regex("""\s*(?::[^={]+)?=\s*sourceCall(?:\(callContext\))? \{""")
        val DECLARATION_START = Regex(
            """^(?:}|@|(?:override|fun|val|var|private|internal|public|protected|suspend|companion|object|class|""" +
                """interface|data|enum|sealed|init|const|lateinit|inline|operator|abstract|open|typealias)\b)""",
        )

        /** [text] with every comment and string character other than a newline replaced by a space. */
        fun codeOnly(text: String): String {
            val code = CharArray(text.length) { if (text[it] == '\n') '\n' else ' ' }
            scanCode(text, 0) { index, char ->
                code[index] = char
                false
            }
            return String(code)
        }

        fun lineOf(text: String, index: Int) = text.substring(0, index).count { it == '\n' } + 1

        /** The next line after [from] that is neither blank nor a comment, or null at the end of the file. */
        fun nextSignificantLine(text: String, from: Int): String? {
            var inBlockComment = false
            for (line in text.substring(from).lines().drop(1)) {
                val trimmed = line.trim()
                when {
                    inBlockComment -> inBlockComment = "*/" !in trimmed
                    trimmed.isEmpty() || trimmed.startsWith("//") -> Unit
                    trimmed.startsWith("/*") -> inBlockComment = "*/" !in trimmed
                    else -> return line
                }
            }
            return null
        }

        /** The `{` that opens the body of the class declared at [from], skipping its constructor. */
        fun classBodyOpen(text: String, from: Int): Int {
            var index = from
            while (text[index] != '{') {
                index = if (text[index] == '(') matchingClose(text, index, '(', ')') + 1 else index + 1
            }
            return index
        }

        /** How many braces are open at [to], counting from [from]. */
        fun braceDepth(text: String, from: Int, to: Int): Int {
            var depth = 0
            scanCode(text, from) { index, char ->
                if (index >= to) return@scanCode true
                if (char == '{') depth++ else if (char == '}') depth--
                false
            }
            return depth
        }

        /** The index of the bracket closing the one at [open]. */
        fun matchingClose(text: String, open: Int, opening: Char, closing: Char): Int {
            var depth = 0
            var result = -1
            scanCode(text, open) { index, char ->
                if (char == opening) depth++
                if (char == closing && --depth == 0) result = index
                result >= 0
            }
            check(result >= 0) { "unbalanced $opening at $open" }
            return result
        }

        /**
         * Calls [visit] for every code character from [from], skipping comments, character literals and string
         * contents (template expressions are visited as code); stops when [visit] returns true.
         */
        fun scanCode(text: String, from: Int, visit: (Int, Char) -> Boolean) {
            val templates = ArrayDeque<Pair<Boolean, Int>>()
            var index = from
            var braces = 0
            while (index < text.length) {
                val char = text[index]
                when {
                    text.startsWith("//", index) -> index = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                    text.startsWith("/*", index) -> index = text.indexOf("*/", index) + 2
                    char == '\'' -> index = skipCharLiteral(text, index)
                    char == '"' -> {
                        val raw = text.startsWith("\"\"\"", index)
                        val end = skipString(text, index, raw)
                        if (end.second) {
                            templates.addLast(raw to braces)
                            braces++
                        }
                        index = end.first
                        continue
                    }
                    char == '}' && templates.isNotEmpty() && templates.last().second == braces - 1 -> {
                        braces--
                        val (raw) = templates.removeLast()
                        val end = skipStringFrom(text, index + 1, raw)
                        if (end.second) {
                            templates.addLast(raw to braces)
                            braces++
                        }
                        index = end.first
                        continue
                    }
                    else -> {
                        if (char == '{') braces++ else if (char == '}') braces--
                        if (visit(index, char)) return
                    }
                }
                index++
            }
        }

        fun skipCharLiteral(text: String, start: Int): Int {
            var index = start + 1
            while (text[index] != '\'') index += if (text[index] == '\\') 2 else 1
            return index
        }

        /** Skips a string opening at [start]; returns where scanning resumes and whether a template began. */
        fun skipString(text: String, start: Int, raw: Boolean) = skipStringFrom(text, start + if (raw) 3 else 1, raw)

        fun skipStringFrom(text: String, from: Int, raw: Boolean): Pair<Int, Boolean> {
            var index = from
            while (true) {
                when {
                    text.startsWith("\${", index) -> return index + 2 to true
                    raw && text.startsWith("\"\"\"", index) -> {
                        index += 3
                        while (index < text.length && text[index] == '"') index++
                        return index to false
                    }
                    !raw && text[index] == '\\' -> index += 2
                    !raw && text[index] == '"' -> return index + 1 to false
                    else -> index++
                }
            }
        }
    }
}
