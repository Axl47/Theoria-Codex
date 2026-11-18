package com.theoriacodex.data.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredFormatTest {
    private val full = """{"name":"a","count":3,"children":[{"label":"x"}],"byKey":{"k":{"label":"y"}},"retired":1}"""

    @Test
    fun `goldens that hold every property pass, retired keys and older partial files included`() {
        assertStoredFormat(
            full to Record("a", 3, listOf(Child("x")), mapOf("k" to Child("y"))),
            """{"name":"old","count":"5"}""" to Record("old", 5),
        )
    }

    @Test
    fun `a golden that decodes differently fails`() {
        val failure = assertThrows(AssertionError::class.java) { assertStoredFormat(full to Record("b")) }

        assertTrue(failure.message, "golden 0 decodes differently" in failure.message.orEmpty())
    }

    @Test
    fun `properties no golden holds fail by path, inside lists and map values too`() {
        val failure = assertThrows(AssertionError::class.java) {
            assertStoredFormat(
                """{"name":"a","count":null,"children":[{}],"byKey":{"k":{}}}""" to
                    Record("a", children = listOf(Child()), byKey = mapOf("k" to Child())),
            )
        }

        assertTrue(failure.message, "[count, children[].label, byKey{}.label]" in failure.message.orEmpty())
    }
}

@Serializable
private data class Record(
    @SerialName("name") val name: String = "",
    @SerialName("count") val count: Int? = null,
    @SerialName("children") val children: List<Child> = emptyList(),
    @SerialName("byKey") val byKey: Map<String, Child> = emptyMap(),
)

@Serializable
private data class Child(@SerialName("label") val label: String = "")
