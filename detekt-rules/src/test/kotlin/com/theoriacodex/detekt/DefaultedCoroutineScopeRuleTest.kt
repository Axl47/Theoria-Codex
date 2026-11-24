package com.theoriacodex.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultedCoroutineScopeRuleTest {
    private fun findings(body: String, imports: String = "import kotlinx.coroutines.CoroutineScope"): Int =
        DefaultedCoroutineScopeRule(Config.empty).lint("$imports\n\n$body\n").size

    @Test
    fun `a CoroutineScope parameter with a default is reported (ADR 0032)`() {
        val reported = listOf(
            "class Store(directory: String, scope: CoroutineScope = newDataStoreScope())",
            "class Store(directory: String) { constructor(scope: CoroutineScope = newDataStoreScope()) : this(\"\") }",
            "fun open(file: String, scope: CoroutineScope? = null) = file",
        )

        assertEquals(reported.map { 1 }, reported.map { findings(it) })
        assertEquals(1, findings("fun open(scope: kotlinx.coroutines.CoroutineScope = newDataStoreScope()) = Unit", imports = ""))
        assertEquals(1, findings("fun open(scope: Scope = newDataStoreScope()) = Unit", imports = "import kotlinx.coroutines.CoroutineScope as Scope"))
        assertEquals(1, findings("fun open(scope: CoroutineScope = newDataStoreScope()) = Unit", imports = "import kotlinx.coroutines.*"))
    }

    @Test
    fun `a scope without a default, another defaulted type, or a type alias is not reported (ADR 0032)`() {
        val silent = listOf(
            "class Store(directory: String, scope: CoroutineScope)",
            "fun open(file: String = \"store.json\", attempts: Int = 3, scope: CoroutineScope) = file",
            "val opener: (CoroutineScope) -> Unit = {}",
            "typealias StoreScope = CoroutineScope\nfun open(scope: StoreScope = newDataStoreScope()) = Unit",
        )

        assertEquals(silent.map { 0 }, silent.map { findings(it) })
        assertEquals(0, findings("class CoroutineScope\nfun open(scope: CoroutineScope = CoroutineScope()) = Unit", imports = ""))
    }

    @Test
    fun `the Theoria rule set runs the defaulted scope rule (ADR 0032)`() {
        val rules = TheoriaRuleSetProvider().instance().rules.values.map { it(Config.empty) }

        assertEquals(1, rules.count { it is DefaultedCoroutineScopeRule })
    }
}
