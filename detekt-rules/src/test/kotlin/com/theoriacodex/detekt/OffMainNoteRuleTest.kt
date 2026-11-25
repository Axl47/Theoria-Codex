package com.theoriacodex.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class OffMainNoteRuleTest {
    private fun messages(code: String): List<String> = OffMainNoteRule(Config.empty).lint(code.trimIndent()).map { it.message }

    @Test
    fun `a note with a data label, in a suspend function or after other work is reported (ADR 0002)`() {
        val reported = mapOf(
            """
            override suspend fun search(
                query: Query,
            ): Page {
                OffMainWork.note(OffMainWork.SOURCE_PARSE)
            }
            """ to listOf(PLACEMENT),
            """
            fun parse(html: String): Document {
                val trimmed = html.trim()
                OffMainWork.note(OffMainWork.SOURCE_PARSE)
            }
            """ to listOf(PLACEMENT),
            """
            fun title(post: Post): Document {
                OffMainWork.note(
                    post.title,
                )
            }
            """ to listOf(LABEL),
            """
            import com.theoriacodex.domain.diagnostics.OffMainWork.note

            fun parse(body: String) {
                note(OffMainWork.SOURCE_PARSE)
            }
            suspend fun load(body: String) {
                note(OffMainWork.SOURCE_PARSE)
            }
            """ to listOf(PLACEMENT),
            """
            suspend fun load(body: String) = withContext(io) {
                OffMainWork.note(OffMainWork.SOURCE_PARSE)
            }
            """ to listOf(PLACEMENT),
            """
            fun parse(body: String) {
                body.let {
                    OffMainWork.note(OffMainWork.SOURCE_PARSE)
                }
            }
            """ to listOf(PLACEMENT),
            """
            val parsed = lazy {
                OffMainWork.note(label = "source-parse")
            }
            """ to listOf(LABEL, PLACEMENT),
        )

        assertEquals(reported.values.toList(), reported.keys.map { messages(it) })
    }

    @Test
    fun `a constant note opening a non-suspend helper is not reported (ADR 0002)`() {
        val code = """
            internal fun parseJsonArray(
                body: String,
                onError: () -> Unit = {},
            ): JsonArray {
                OffMainWork
                    .note(
                        OffMainWork.SOURCE_PARSE,
                    )
                return parse(body)
            }
            private fun SourceHttpResponse.jsonBody() = body.takeIf(String::isNotBlank)?.let {
                OffMainWork.note(com.theoriacodex.domain.diagnostics.OffMainWork.SOURCE_PARSE)
            }
            private fun parseJson(body: String) = try {
                OffMainWork.note(OffMainWork.SOURCE_PARSE)
                Json.parseToJsonElement(body)
            } catch (error: RuntimeException) {
                throw error
            }
            fun parseHtml(html: String): Document {
                com.theoriacodex.domain.diagnostics.OffMainWork.note(label = OffMainWork.SOURCE_PARSE)
                return Jsoup.parse(html)
            }
            suspend fun explain() {
                log("call OffMainWork.note(first) in the helper {")
            }
            suspend fun load(body: String) {
                note(body)
            }
        """

        assertEquals(emptyList<String>(), messages(code))
    }

    @Test
    fun `the Theoria rule set runs the off-main note rule (ADR 0002)`() {
        val rules = TheoriaRuleSetProvider().instance().rules.values.map { it(Config.empty) }

        assertEquals(1, rules.count { it is OffMainNoteRule })
    }

    private companion object {
        const val LABEL = "Note a constant OffMainWork label, never data."
        const val PLACEMENT = "Call OffMainWork.note first in the non-suspend function that does the work."
    }
}
