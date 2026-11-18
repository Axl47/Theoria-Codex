package com.theoriacodex.data.storage

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GsonParityTest {
    @Test
    fun `a record mirroring its Gson names passes, including older and newer goldens`() {
        assertGsonParity(
            samples = listOf(Mirrored("a&b <c>", 3, listOf(Child("x='y'"))), Mirrored()),
            goldenJson = listOf("""{"name":"old","count":"5","retired":{"nested":[1]},"children":[{}]}"""),
        )
    }

    @Test
    fun `a renamed key fails and names both spellings`() {
        val failure = assertThrows(AssertionError::class.java) {
            assertGsonParity(samples = listOf(Renamed(count = 1)))
        }

        assertTrue(failure.message, "[count]" in failure.message.orEmpty() && "[total]" in failure.message.orEmpty())
    }

    @Test
    fun `properties no sample emits fail, at any depth`() {
        val failure = assertThrows(AssertionError::class.java) {
            assertGsonParity(samples = listOf(Mirrored(name = "only")))
        }

        assertTrue(failure.message, "[count, children[].label]" in failure.message.orEmpty())
    }
}

@Serializable
private data class Mirrored(
    @field:SerializedName("name") @SerialName("name") val name: String = "",
    @field:SerializedName("count") @SerialName("count") val count: Int? = null,
    @field:SerializedName("children") @SerialName("children") val children: List<Child> = emptyList(),
)

@Serializable
private data class Child(@field:SerializedName("label") @SerialName("label") val label: String = "")

@Serializable
private data class Renamed(@field:SerializedName("count") @SerialName("total") val count: Int = 0)
