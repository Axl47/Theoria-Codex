package com.theoriacodex.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class CancellationCatchRuleTest {
    private fun messages(
        catches: String,
        cancellationImport: String = "kotlinx.coroutines.CancellationException",
    ): List<String> =
        CancellationCatchRule(Config.empty).lint(
            """
            import $cancellationImport
            import java.io.IOException

            fun run(block: () -> Unit) {
                try { block() } $catches
            }
            """.trimIndent(),
        ).map { it.message }

    @Test
    fun `flags a rethrow before catching Throwable`() {
        assertEquals(
            listOf("Use runCatchingPreservingCancellation instead of rethrowing CancellationException by hand."),
            messages("catch (e: CancellationException) { throw e } catch (x: Throwable) { println(x) }"),
        )
    }

    @Test
    fun `flags a rethrow before catching Exception`() {
        assertEquals(
            listOf("Use runCatchingExceptionsPreservingCancellation instead of rethrowing CancellationException by hand."),
            messages("catch (e: CancellationException) { throw e } catch (x: Exception) { println(x) }"),
        )
    }

    @Test
    fun `ignores a try with finally`() {
        assertEquals(
            emptyList<String>(),
            messages("catch (e: CancellationException) { throw e } catch (x: Exception) { println(x) } finally { println() }"),
        )
    }

    @Test
    fun `ignores a selective second catch`() {
        assertEquals(
            emptyList<String>(),
            messages("catch (e: CancellationException) { throw e } catch (x: IOException) { println(x) }"),
        )
    }

    @Test
    fun `ignores a third catch`() {
        assertEquals(
            emptyList<String>(),
            messages(
                "catch (e: CancellationException) { throw e } catch (x: IOException) { println(x) } " +
                    "catch (x: Exception) { println(x) }",
            ),
        )
    }

    @Test
    fun `flags an aliased CancellationException rethrown in parentheses`() {
        assertEquals(
            listOf("Use runCatchingPreservingCancellation instead of rethrowing CancellationException by hand."),
            messages(
                "catch (e: Cancelled) { throw (e) } catch (x: Throwable) { println(x) }",
                cancellationImport = "kotlinx.coroutines.CancellationException as Cancelled",
            ),
        )
    }

    @Test
    fun `ignores a CancellationException from another package`() {
        assertEquals(
            emptyList<String>(),
            messages(
                "catch (e: CancellationException) { throw e } catch (x: Exception) { println(x) }",
                cancellationImport = "other.CancellationException",
            ),
        )
    }

    @Test
    fun `ignores a bare CancellationException that a wildcard import may shadow`() {
        assertEquals(
            emptyList<String>(),
            messages(
                "catch (e: CancellationException) { throw e } catch (x: Exception) { println(x) }",
                cancellationImport = "other.*",
            ),
        )
    }
}
