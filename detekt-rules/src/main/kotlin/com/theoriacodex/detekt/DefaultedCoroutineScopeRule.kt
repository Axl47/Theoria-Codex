package com.theoriacodex.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtUserType

class DefaultedCoroutineScopeRule(config: Config) : Rule(
    config,
    "A CoroutineScope parameter takes no default, so every caller decides who closes the scope.",
) {
    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        if (!parameter.hasDefaultValue()) return
        val written = parameter.typeReference?.typeElement ?: return
        val type = ((written as? KtNullableType)?.innerType ?: written) as? KtUserType ?: return
        val name = type.referenceExpression?.getReferencedName() ?: return
        val qualifier = type.qualifier?.text
        val resolved = when {
            qualifier != null -> "$qualifier.$name"
            else -> parameter.containingKtFile.importDirectives.let { imports ->
                imports.firstOrNull { !it.isAllUnder && (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == name }
                    ?.importedFqName?.asString()
                    ?: "$COROUTINES.$name".takeIf { imports.any { it.isAllUnder && it.importedFqName?.asString() == COROUTINES } }
            }
        }
        if (resolved != COROUTINE_SCOPE) return
        report(Finding(Entity.from(parameter), "Take the CoroutineScope with no default value."))
    }

    private companion object {
        const val COROUTINES = "kotlinx.coroutines"
        const val COROUTINE_SCOPE = "$COROUTINES.CoroutineScope"
    }
}
