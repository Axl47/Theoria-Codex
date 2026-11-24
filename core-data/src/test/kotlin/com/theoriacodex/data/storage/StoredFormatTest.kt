package com.theoriacodex.data.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredFormatTest {
    private val full =
        """{"name":"a","count":3,"children":[{"label":"x"}],"groups":[[{"label":"g"}]],"byKey":{"k":{"label":"y"}},"retired":1}"""

    @Test
    fun `goldens that hold every property pass, retired keys and older partial files included (ADR 0034)`() {
        assertStoredFormat(
            full to Record("a", 3, listOf(Child("x")), listOf(listOf(Child("g"))), mapOf("k" to Child("y"))),
            """{"name":"old","count":"5"}""" to Record("old", 5),
            retiredKeys = setOf("retired"),
        )
    }

    @Test
    fun `a golden that decodes differently fails (ADR 0034)`() {
        val failure = assertThrows(AssertionError::class.java) { assertStoredFormat(full to Record("b")) }

        assertTrue(failure.message, "golden 0 decodes differently" in failure.message.orEmpty())
    }

    @Test
    fun `a key the model does not declare fails by path unless it is listed as retired (ADR 0034)`() {
        val stale = full.replace("""{"label":"x"}""", """{"label":"x","stale":true}""")
            .replace("""{"label":"y"}""", """{"label":"y","stale":true}""")
        val failure = assertThrows(AssertionError::class.java) {
            assertStoredFormat(
                stale to Record("a", 3, listOf(Child("x")), listOf(listOf(Child("g"))), mapOf("k" to Child("y"))),
                retiredKeys = setOf("retired"),
            )
        }

        assertTrue(failure.message, "[children[].stale, byKey{}.stale]" in failure.message.orEmpty())
    }

    @Test
    fun `a retired nested path never excuses a key spelled like it (ADR 0034)`() {
        val literal = full.replace(""""retired":1""", """"retired":1,"children[].stale":true""")
        val failure = assertThrows(AssertionError::class.java) {
            assertStoredFormat(
                literal to Record("a", 3, listOf(Child("x")), listOf(listOf(Child("g"))), mapOf("k" to Child("y"))),
                retiredKeys = setOf("retired", "children[].stale"),
            )
        }

        assertTrue(failure.message, """[children\[\]\.stale]""" in failure.message.orEmpty())
    }

    @Test
    fun `properties no golden holds fail by path, inside nested lists and map values too (ADR 0034)`() {
        val failure = assertThrows(AssertionError::class.java) {
            assertStoredFormat(
                """{"name":"a","count":null,"children":[{}],"groups":[[{}]],"byKey":{"k":{}}}""" to
                    Record("a", children = listOf(Child()), groups = listOf(listOf(Child())), byKey = mapOf("k" to Child())),
            )
        }

        assertTrue(failure.message, "[count, children[].label, groups[][].label, byKey{}.label]" in failure.message.orEmpty())
    }
}

@Serializable
private data class Record(
    @SerialName("name") val name: String = "",
    @SerialName("count") val count: Int? = null,
    @SerialName("children") val children: List<Child> = emptyList(),
    @SerialName("groups") val groups: List<List<Child>> = emptyList(),
    @SerialName("byKey") val byKey: Map<String, Child> = emptyMap(),
)

@Serializable
private data class Child(@SerialName("label") val label: String = "")
