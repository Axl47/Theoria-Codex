package com.theoriacodex.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class StoredJsonUsesDurableJsonRuleTest {
    private fun findings(body: String, imports: String = "import kotlinx.serialization.json.Json"): Int =
        StoredJsonUsesDurableJsonRule(Config.empty).lint("$imports\n\n$body\n").size

    @Test
    fun `building or calling kotlinx's Json in an expression is reported (ADR 0033)`() {
        val reported = mapOf(
            "val json = Json { ignoreUnknownKeys = true }" to 1,
            "val json = Json(DurableJson) { prettyPrint = true }" to 1,
            "val json = Json(from = Json) { }" to 2,
            "val json = Json . Default" to 1,
            "val text = Json\n    .encodeToString(value)" to 1,
            "val format: Json = Json" to 1,
            "fun decode(json: Json = Json) = json" to 1,
            "val tree = parse(Json, raw)" to 1,
        )

        assertEquals(reported, reported.mapValues { (body, _) -> findings(body) })
        assertEquals(1, findings("val json = Wire { }", imports = "import kotlinx.serialization.json.Json as Wire"))
        assertEquals(1, findings("val tree = Json.parseToJsonElement(raw)", imports = "import kotlinx.serialization.json.*"))
        assertEquals(1, findings("val text = kotlinx.serialization.json.Json.decodeFromString<String>(raw)", imports = ""))
    }

    @Test
    fun `Json as a type, inside a string, or a different Json is not reported (ADR 0033)`() {
        val silent = listOf(
            "fun storageJson(): Json { return DurableJson }",
            "fun storageJson(): Json = DurableJson",
            "fun Json.pretty(): String = \"\"",
            "val formats: List<Json> = emptyList()\nval read: (Json) -> Unit = {}\nval maybe: Json? = null",
            "val note = \"Json.Default and Json { }\"",
        )

        assertEquals(silent.map { 0 }, silent.map { findings(it) })
        assertEquals(0, findings("object Json { fun parse() = 1 }\nval parsed = Json.parse()", imports = ""))
        assertEquals(0, findings("val type: kotlinx.serialization.json.Json = DurableJson", imports = ""))
    }

    @Test
    fun `the Theoria rule set runs the stored JSON rule (ADR 0033)`() {
        val rules = TheoriaRuleSetProvider().instance().rules.values.map { it(Config.empty) }

        assertEquals(1, rules.count { it is StoredJsonUsesDurableJsonRule })
    }

    @Test
    fun `a suppression on one declaration excuses only that declaration (ADR 0033)`() {
        val body = """
            @Suppress("StoredJsonUsesDurableJsonRule")
            fun storedMemo(text: String) = Json.parseToJsonElement(text)

            fun other(text: String) = Json.parseToJsonElement(text)
        """.trimIndent()

        assertEquals(1, findings(body))
    }
}
